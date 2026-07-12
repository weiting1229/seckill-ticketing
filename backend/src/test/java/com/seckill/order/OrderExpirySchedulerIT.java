package com.seckill.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.seckill.auth.domain.Role;
import com.seckill.common.id.IdGenerator;
import com.seckill.event.service.ReconcileService;
import com.seckill.order.domain.Order;
import com.seckill.order.domain.OrderStatus;
import com.seckill.order.mapper.OrderMapper;
import com.seckill.order.service.OrderExpiryScheduler;
import com.seckill.support.AbstractAdminIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 兜底排程(子項 5)整合測試:直呼 {@link OrderExpiryScheduler#sweepOnce()}(不依賴排程時序)。
 *
 * <p>驗證:過期 PENDING 訂單被取消 + 三方回補一致;未過期 PENDING 不動;已 PAID 不動。
 * 斷言目標訂單的最終狀態(而非全域取消筆數),避開共用 DB 中其他測試殘留的影響。
 */
class OrderExpirySchedulerIT extends AbstractAdminIntegrationTest {

    private static final String STOCK_KEY = "seckill:stock:";
    private static final String BOUGHT_KEY = "seckill:bought:";

    @Autowired
    OrderExpiryScheduler scheduler;

    @Autowired
    OrderMapper orderMapper;

    @Autowired
    ReconcileService reconcileService;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    IdGenerator idGenerator;

    // ---- 測試夾具 ----

    private String createEvent(String admin) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", "兜底測試");
        m.put("venue", "Taipei");
        m.put("eventTime", Instant.now().plus(10, ChronoUnit.DAYS).toString());
        return json(post("/api/v1/admin/events", admin, m)).path("data").path("id").asText();
    }

    private long createTicketType(String admin, String eventId, int totalStock) {
        Map<String, Object> m = new HashMap<>();
        m.put("eventId", eventId);
        m.put("name", "看台");
        m.put("price", "1200.00");
        m.put("totalStock", totalStock);
        m.put("seckillStart", Instant.now().minus(1, ChronoUnit.HOURS).toString());
        m.put("seckillEnd", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        return Long.parseLong(json(post("/api/v1/admin/ticket-types", admin, m)).path("data").path("id").asText());
    }

    private long insertOrder(long eventId, long ticketTypeId, long userId, OrderStatus status, Instant expireAt) {
        long orderId = idGenerator.nextId();
        Instant now = Instant.now();
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setEventId(eventId);
        order.setTicketTypeId(ticketTypeId);
        order.setPrice(new BigDecimal("1200.00"));
        order.setStatus(status);
        order.setRequestId(UUID.randomUUID().toString());
        order.setCreatedAt(now.minus(20, ChronoUnit.MINUTES));
        order.setUpdatedAt(now.minus(20, ChronoUnit.MINUTES));
        order.setExpireAt(expireAt);
        orderMapper.insert(order);
        return orderId;
    }

    /** 模擬「已成交、待支付且已逾時」的一致狀態,回傳 orderId。 */
    private long seedExpiredSoldOrder(long eventId, long ticketTypeId, long userId, int totalStock) {
        int remaining = totalStock - 1;
        jdbcTemplate.update("UPDATE ticket_types SET stock_remaining = ? WHERE id = ?", remaining, ticketTypeId);
        redisTemplate.opsForValue().set(STOCK_KEY + ticketTypeId, String.valueOf(remaining));
        redisTemplate.opsForSet().add(BOUGHT_KEY + ticketTypeId, String.valueOf(userId));
        long orderId = insertOrder(eventId, ticketTypeId, userId, OrderStatus.PENDING_PAYMENT,
                Instant.now().minus(5, ChronoUnit.MINUTES));
        jdbcTemplate.update(
                "INSERT INTO stock_logs (id, ticket_type_id, order_id, delta, type, created_at) "
                        + "VALUES (?, ?, ?, -1, 'DEDUCT', ?)",
                idGenerator.nextId(), ticketTypeId, orderId, java.sql.Timestamp.from(Instant.now()));
        return orderId;
    }

    private Integer dbStock(long ticketTypeId) {
        return jdbcTemplate.queryForObject(
                "SELECT stock_remaining FROM ticket_types WHERE id = ?", Integer.class, ticketTypeId);
    }

    // ---- (c) 過期 PENDING 訂單被兜底取消 + 回補;未過期不動 ----

    @Test
    void sweepCancelsExpiredPendingAndLeavesFreshOrderUntouched() {
        String admin = createAdminToken();
        long eventId = Long.parseLong(createEvent(admin));
        long ttId = createTicketType(admin, String.valueOf(eventId), 10);
        long userId = insertUser(Role.USER).getId();
        long expiredOrderId = seedExpiredSoldOrder(eventId, ttId, userId, 10);

        // 對照:另一 user 的未過期 PENDING 訂單(expire_at 在未來)不應被掃到
        long freshUserId = insertUser(Role.USER).getId();
        long freshOrderId = insertOrder(eventId, idGenerator.nextId(), freshUserId,
                OrderStatus.PENDING_PAYMENT, Instant.now().plus(10, ChronoUnit.MINUTES));

        int cancelled = scheduler.sweepOnce();

        assertThat(cancelled).isGreaterThanOrEqualTo(1); // 至少含本測試的過期訂單
        // 過期訂單被取消 + 三方回補一致
        assertThat(orderMapper.findById(expiredOrderId).getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(orderMapper.findById(expiredOrderId).getPaidAt()).isNull();
        assertThat(dbStock(ttId)).isEqualTo(10);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY + ttId)).isEqualTo("10");
        assertThat(redisTemplate.opsForSet().isMember(BOUGHT_KEY + ttId, String.valueOf(userId))).isFalse();
        Integer revertLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_logs WHERE order_id = ? AND type = 'REVERT'", Integer.class, expiredOrderId);
        assertThat(revertLogs).isEqualTo(1);
        assertThat(reconcileService.reconcile(ttId).consistent()).isTrue();

        // 未過期訂單不動
        assertThat(orderMapper.findById(freshOrderId).getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    // ---- 已 PAID 訂單即使 expire_at 過去也不被掃(status 不符,冪等 no-op)----

    @Test
    void sweepDoesNotTouchPaidOrders() {
        String admin = createAdminToken();
        long eventId = Long.parseLong(createEvent(admin));
        long ttId = createTicketType(admin, String.valueOf(eventId), 10);
        long userId = insertUser(Role.USER).getId();
        // PAID 但 expire_at 在過去(付款後才過期);不應被兜底取消
        long paidOrderId = insertOrder(eventId, ttId, userId, OrderStatus.PAID,
                Instant.now().minus(5, ChronoUnit.MINUTES));
        jdbcTemplate.update("UPDATE orders SET paid_at = now() WHERE id = ?", paidOrderId);
        jdbcTemplate.update("UPDATE ticket_types SET stock_remaining = 9 WHERE id = ?", ttId);

        scheduler.sweepOnce();

        assertThat(orderMapper.findById(paidOrderId).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(dbStock(ttId)).isEqualTo(9); // 未回補
    }
}
