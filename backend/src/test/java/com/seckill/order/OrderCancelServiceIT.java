package com.seckill.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.seckill.auth.domain.Role;
import com.seckill.common.id.IdGenerator;
import com.seckill.event.dto.ReconcileResponse;
import com.seckill.event.service.ReconcileService;
import com.seckill.order.domain.Order;
import com.seckill.order.domain.OrderStatus;
import com.seckill.order.mapper.OrderMapper;
import com.seckill.order.service.OrderCancelService;
import com.seckill.support.AbstractAdminIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 共用取消回補邏輯(子項 1)整合測試:直呼 {@link OrderCancelService}(不經 MQ,確定性高)。
 *
 * <p>驗證:取消 → EXPIRED + DB/Redis 庫存回補 + REVERT 流水 + 三方對帳一致;冪等(重複取消 no-op);
 * PAID 訂單不被取消;cancel-vs-cancel 併發恰一方成功、不重複回補(條件 UPDATE 天然互斥)。
 */
class OrderCancelServiceIT extends AbstractAdminIntegrationTest {

    private static final String STOCK_KEY = "seckill:stock:";
    private static final String BOUGHT_KEY = "seckill:bought:";

    @Autowired
    OrderCancelService cancelService;

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
        m.put("title", "取消測試");
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

    /**
     * 模擬「一次已成交、待支付」的一致狀態:DB 扣 1、Redis 扣 1、已購含該 user、
     * 訂單 PENDING 且已逾時、DEDUCT 流水一筆。回傳訂單 id。
     */
    private long seedPendingSoldOrder(long eventId, long ticketTypeId, long userId, int totalStock) {
        int remaining = totalStock - 1;
        jdbcTemplate.update("UPDATE ticket_types SET stock_remaining = ? WHERE id = ?", remaining, ticketTypeId);
        redisTemplate.opsForValue().set(STOCK_KEY + ticketTypeId, String.valueOf(remaining));
        redisTemplate.opsForSet().add(BOUGHT_KEY + ticketTypeId, String.valueOf(userId));

        long orderId = idGenerator.nextId();
        Instant now = Instant.now();
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setEventId(eventId);
        order.setTicketTypeId(ticketTypeId);
        order.setPrice(new BigDecimal("1200.00"));
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setRequestId(UUID.randomUUID().toString());
        order.setCreatedAt(now.minus(20, ChronoUnit.MINUTES));
        order.setUpdatedAt(now.minus(20, ChronoUnit.MINUTES));
        order.setExpireAt(now.minus(5, ChronoUnit.MINUTES)); // 已逾時
        orderMapper.insert(order);

        jdbcTemplate.update(
                "INSERT INTO stock_logs (id, ticket_type_id, order_id, delta, type, created_at) "
                        + "VALUES (?, ?, ?, -1, 'DEDUCT', ?)",
                idGenerator.nextId(), ticketTypeId, orderId, java.sql.Timestamp.from(now));
        return orderId;
    }

    private Integer dbStock(long ticketTypeId) {
        return jdbcTemplate.queryForObject(
                "SELECT stock_remaining FROM ticket_types WHERE id = ?", Integer.class, ticketTypeId);
    }

    private int revertLogCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_logs WHERE order_id = ? AND type = 'REVERT' AND delta = 1",
                Integer.class, orderId);
    }

    // ---- (b-core) 取消 → EXPIRED + 三方回補 + 對帳一致 ----

    @Test
    void cancelExpiredRevertsStockAcrossDbRedisAndLog() {
        String admin = createAdminToken();
        long eventId = Long.parseLong(createEvent(admin));
        long ttId = createTicketType(admin, String.valueOf(eventId), 10);
        long userId = insertUser(Role.USER).getId();
        long orderId = seedPendingSoldOrder(eventId, ttId, userId, 10);

        boolean cancelled = cancelService.cancelExpired(orderId);

        assertThat(cancelled).isTrue();
        assertThat(orderMapper.findById(orderId).getStatus()).isEqualTo(OrderStatus.EXPIRED);
        // paid_at 不動(仍為 null)
        assertThat(orderMapper.findById(orderId).getPaidAt()).isNull();
        assertThat(dbStock(ttId)).isEqualTo(10);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY + ttId)).isEqualTo("10");
        assertThat(redisTemplate.opsForSet().isMember(BOUGHT_KEY + ttId, String.valueOf(userId))).isFalse();
        assertThat(revertLogCount(orderId)).isEqualTo(1);

        ReconcileResponse rec = reconcileService.reconcile(ttId);
        assertThat(rec.consistent()).isTrue();
        assertThat(rec.soldByDb()).isZero();
        assertThat(rec.validOrderCount()).isZero();
    }

    // ---- 冪等:重複取消 no-op,不重複回補 ----

    @Test
    void secondCancelIsNoOp() {
        String admin = createAdminToken();
        long eventId = Long.parseLong(createEvent(admin));
        long ttId = createTicketType(admin, String.valueOf(eventId), 10);
        long userId = insertUser(Role.USER).getId();
        long orderId = seedPendingSoldOrder(eventId, ttId, userId, 10);

        assertThat(cancelService.cancelExpired(orderId)).isTrue();
        assertThat(cancelService.cancelExpired(orderId)).isFalse();

        // 僅回補一次
        assertThat(dbStock(ttId)).isEqualTo(10);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY + ttId)).isEqualTo("10");
        assertThat(revertLogCount(orderId)).isEqualTo(1);
    }

    // ---- PAID 訂單不可被超時取消(狀態機守衛)----

    @Test
    void paidOrderIsNotCancelled() {
        String admin = createAdminToken();
        long eventId = Long.parseLong(createEvent(admin));
        long ttId = createTicketType(admin, String.valueOf(eventId), 10);
        long userId = insertUser(Role.USER).getId();
        long orderId = seedPendingSoldOrder(eventId, ttId, userId, 10);
        jdbcTemplate.update("UPDATE orders SET status = 'PAID', paid_at = now() WHERE id = ?", orderId);

        assertThat(cancelService.cancelExpired(orderId)).isFalse();

        assertThat(orderMapper.findById(orderId).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(dbStock(ttId)).isEqualTo(9); // 未回補
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY + ttId)).isEqualTo("9");
        assertThat(revertLogCount(orderId)).isZero();
    }

    // ---- 併發:cancel vs cancel 對同一訂單,恰一方成功、不重複回補 ----

    @Test
    void concurrentCancelRevertsExactlyOnce() throws Exception {
        String admin = createAdminToken();
        long eventId = Long.parseLong(createEvent(admin));
        long ttId = createTicketType(admin, String.valueOf(eventId), 10);
        long userId = insertUser(Role.USER).getId();
        long orderId = seedPendingSoldOrder(eventId, ttId, userId, 10);

        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    barrier.await();
                    if (cancelService.cancelExpired(orderId)) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 併發下的例外不影響斷言(以最終狀態為準)
                } finally {
                    done.countDown();
                }
            }).start();
        }
        done.await();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(orderMapper.findById(orderId).getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(dbStock(ttId)).isEqualTo(10); // 僅回補一次
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY + ttId)).isEqualTo("10");
        assertThat(revertLogCount(orderId)).isEqualTo(1);
        assertThat(reconcileService.reconcile(ttId).consistent()).isTrue();
    }
}
