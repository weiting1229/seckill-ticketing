package com.seckill.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.seckill.auth.domain.Role;
import com.seckill.auth.domain.User;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.id.IdGenerator;
import com.seckill.order.domain.Order;
import com.seckill.order.domain.OrderStatus;
import com.seckill.order.mapper.OrderMapper;
import com.seckill.order.service.OrderCancelService;
import com.seckill.order.service.OrderService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 模擬支付(子項 3)整合測試:支付成功轉 PAID、重複支付非法(4002)、他人訂單 404(4001)、
 * 不存在 404、支付 vs 超時取消併發恰一方成功(條件 UPDATE 天然互斥)。
 */
class OrderPayIT extends AbstractAdminIntegrationTest {

    private static final String STOCK_KEY = "seckill:stock:";
    private static final String BOUGHT_KEY = "seckill:bought:";

    @Autowired
    OrderMapper orderMapper;

    @Autowired
    OrderService orderService;

    @Autowired
    OrderCancelService cancelService;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    IdGenerator idGenerator;

    // ---- 測試夾具 ----

    private String createEvent(String admin) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", "支付測試");
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

    /** 插入一筆該 user 的 PENDING 訂單(不涉庫存);回傳 orderId。 */
    private long insertPendingOrder(long eventId, long ticketTypeId, long userId) {
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
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order.setExpireAt(now.plus(15, ChronoUnit.MINUTES));
        orderMapper.insert(order);
        return orderId;
    }

    /** 模擬「已成交、待支付」的一致狀態(供併發回補斷言):DB 扣 1、Redis 扣 1、已購含 user、DEDUCT 流水。 */
    private long seedPendingSoldOrder(long eventId, long ticketTypeId, long userId, int totalStock) {
        int remaining = totalStock - 1;
        jdbcTemplate.update("UPDATE ticket_types SET stock_remaining = ? WHERE id = ?", remaining, ticketTypeId);
        redisTemplate.opsForValue().set(STOCK_KEY + ticketTypeId, String.valueOf(remaining));
        redisTemplate.opsForSet().add(BOUGHT_KEY + ticketTypeId, String.valueOf(userId));
        long orderId = insertPendingOrder(eventId, ticketTypeId, userId);
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

    private int revertLogCount(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_logs WHERE order_id = ? AND type = 'REVERT'", Integer.class, orderId);
    }

    // ---- (a) 支付成功 → PAID + paid_at;再次支付 → 4002 ----

    @Test
    void paySucceedsThenSecondPayIsInvalidTransition() {
        String admin = createAdminToken();
        long eventId = Long.parseLong(createEvent(admin));
        long ttId = createTicketType(admin, String.valueOf(eventId), 10);
        User user = insertUser(Role.USER);
        String token = tokenFor(user);
        long orderId = insertPendingOrder(eventId, ttId, user.getId());

        ResponseEntity<String> pay = post("/api/v1/orders/" + orderId + "/pay", token, null);
        assertThat(pay.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = json(pay).path("data");
        assertThat(json(pay).path("code").asInt()).isZero();
        assertThat(data.path("status").asText()).isEqualTo("PAID");
        assertThat(data.path("paidAt").isNull()).isFalse();
        assertThat(data.path("id").asText()).isEqualTo(String.valueOf(orderId));

        assertThat(orderMapper.findById(orderId).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(orderMapper.findById(orderId).getPaidAt()).isNotNull();

        // 再次支付:非法轉移
        ResponseEntity<String> again = post("/api/v1/orders/" + orderId + "/pay", token, null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(again).path("code").asInt()).isEqualTo(4002);
    }

    // ---- (b) 他人訂單 → 404 / 4001(不洩漏存在性),且不被支付 ----

    @Test
    void payingOthersOrderReturns404() {
        String admin = createAdminToken();
        long eventId = Long.parseLong(createEvent(admin));
        long ttId = createTicketType(admin, String.valueOf(eventId), 10);
        User owner = insertUser(Role.USER);
        User attacker = insertUser(Role.USER);
        String attackerToken = tokenFor(attacker);
        long orderId = insertPendingOrder(eventId, ttId, owner.getId());

        ResponseEntity<String> pay = post("/api/v1/orders/" + orderId + "/pay", attackerToken, null);
        assertThat(pay.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(pay).path("code").asInt()).isEqualTo(4001);
        // 他人訂單未被動到
        assertThat(orderMapper.findById(orderId).getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    // ---- (c) 不存在的訂單 → 404 / 4001 ----

    @Test
    void payingNonexistentOrderReturns404() {
        String token = tokenFor(insertUser(Role.USER));
        ResponseEntity<String> pay = post("/api/v1/orders/" + idGenerator.nextId() + "/pay", token, null);
        assertThat(pay.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(pay).path("code").asInt()).isEqualTo(4001);
    }

    // ---- (d) 支付 vs 超時取消併發:恰一方成功、不重複回補、不超賣 ----

    @Test
    void concurrentPayVsCancelExactlyOneWins() throws Exception {
        String admin = createAdminToken();
        long eventId = Long.parseLong(createEvent(admin));
        long ttId = createTicketType(admin, String.valueOf(eventId), 10);
        User user = insertUser(Role.USER);
        long orderId = seedPendingSoldOrder(eventId, ttId, user.getId(), 10);

        int perSide = 5;
        int threads = perSide * 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger paySuccess = new AtomicInteger();
        AtomicInteger cancelSuccess = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            boolean payer = i % 2 == 0;
            new Thread(() -> {
                try {
                    barrier.await();
                    if (payer) {
                        try {
                            orderService.pay(user.getId(), orderId);
                            paySuccess.incrementAndGet();
                        } catch (BusinessException ignored) {
                            // 競態落敗:訂單已被取消 → 非法轉移
                        }
                    } else if (cancelService.cancelExpired(orderId)) {
                        cancelSuccess.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 以最終狀態為準
                } finally {
                    done.countDown();
                }
            }).start();
        }
        done.await();

        // 支付與取消對同一 PENDING 訂單:恰一方成功
        assertThat(paySuccess.get() + cancelSuccess.get()).isEqualTo(1);
        Order order = orderMapper.findById(orderId);
        if (paySuccess.get() == 1) {
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(dbStock(ttId)).isEqualTo(9);     // 支付勝出:不回補
            assertThat(revertLogCount(orderId)).isZero();
        } else {
            assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
            assertThat(dbStock(ttId)).isEqualTo(10);    // 取消勝出:回補恰一次
            assertThat(revertLogCount(orderId)).isEqualTo(1);
        }
    }
}
