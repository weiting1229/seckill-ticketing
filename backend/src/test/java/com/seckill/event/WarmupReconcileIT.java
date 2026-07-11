package com.seckill.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.seckill.auth.domain.Role;
import com.seckill.common.id.IdGenerator;
import com.seckill.support.AbstractAdminIntegrationTest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 庫存預熱冪等性與對帳三方一致整合測試(M2 核心驗收)。
 */
class WarmupReconcileIT extends AbstractAdminIntegrationTest {

    private static final String STOCK_KEY = "seckill:stock:";

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    IdGenerator idGenerator;

    private String createEvent(String admin) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", "預熱對帳");
        m.put("venue", "Kaohsiung");
        m.put("eventTime", Instant.now().plus(10, ChronoUnit.DAYS).toString());
        return json(post("/api/v1/admin/events", admin, m)).path("data").path("id").asText();
    }

    private String createTicketType(String admin, String eventId, int totalStock) {
        Map<String, Object> m = new HashMap<>();
        m.put("eventId", eventId);
        m.put("name", "看台");
        m.put("price", "1000.00");
        m.put("totalStock", totalStock);
        m.put("seckillStart", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        m.put("seckillEnd", Instant.now().plus(2, ChronoUnit.DAYS).toString());
        return json(post("/api/v1/admin/ticket-types", admin, m)).path("data").path("id").asText();
    }

    // ---- 預熱冪等:重複呼叫不覆蓋已扣減庫存 ----

    @Test
    void warmupIsIdempotentAndDoesNotOverwriteDecrementedStock() {
        String admin = createAdminToken();
        String eventId = createEvent(admin);
        String ttId = createTicketType(admin, eventId, 1000);

        // 首次預熱:alreadyWarmed=false,Redis = 1000
        ResponseEntity<String> first = post("/api/v1/admin/ticket-types/" + ttId + "/warmup", admin, null);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode f = json(first).path("data");
        assertThat(f.path("alreadyWarmed").asBoolean()).isFalse();
        assertThat(f.path("status").asText()).isEqualTo("ONLINE");
        assertThat(f.path("redisStockRemaining").asInt()).isEqualTo(1000);

        // 模擬熱路徑扣減:Redis 減 30 → 970
        redisTemplate.opsForValue().increment(STOCK_KEY + ttId, -30);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY + ttId)).isEqualTo("970");

        // 再次預熱:alreadyWarmed=true,且 Redis 現值(970)不被蓋回 1000
        ResponseEntity<String> second = post("/api/v1/admin/ticket-types/" + ttId + "/warmup", admin, null);
        JsonNode s = json(second).path("data");
        assertThat(s.path("alreadyWarmed").asBoolean()).isTrue();
        assertThat(s.path("redisStockRemaining").asInt()).isEqualTo(970);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY + ttId)).isEqualTo("970");
    }

    // ---- 併發預熱:多執行緒同時預熱,Redis 恰為總量、恰上線一次 ----

    @Test
    void concurrentWarmupConvergesToTotalStock() throws Exception {
        String admin = createAdminToken();
        String eventId = createEvent(admin);
        int total = 500;
        String ttId = createTicketType(admin, eventId, total);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> tasks = java.util.Collections.nCopies(threads, () ->
                    post("/api/v1/admin/ticket-types/" + ttId + "/warmup", admin, null)
                            .getStatusCode().value());
            List<Future<Integer>> results = pool.invokeAll(tasks);
            for (Future<Integer> r : results) {
                assertThat(r.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdown();
        }

        // 併發下 Lua 條件寫入保證只設一次,Redis 恰為 total(無覆蓋、無累加)
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY + ttId)).isEqualTo(String.valueOf(total));
        assertThat(json(get("/api/v1/admin/ticket-types/" + ttId, admin))
                .path("data").path("status").asText()).isEqualTo("ONLINE");
    }

    // ---- 對帳三方一致 ----

    @Test
    void reconcileReportsConsistentAfterWarmupAndSimulatedPurchase() {
        String admin = createAdminToken();
        String eventId = createEvent(admin);
        long ttId = Long.parseLong(createTicketType(admin, eventId, 1000));

        post("/api/v1/admin/ticket-types/" + ttId + "/warmup", admin, null);

        // 剛預熱、未售出:三方一致,售出 0
        JsonNode r0 = json(get("/api/v1/admin/ticket-types/" + ttId + "/reconcile", admin)).path("data");
        assertThat(r0.path("consistent").asBoolean()).isTrue();
        assertThat(r0.path("soldByDb").asInt()).isZero();
        assertThat(r0.path("dbStockRemaining").asInt()).isEqualTo(1000);
        assertThat(r0.path("redisStockRemaining").asInt()).isEqualTo(1000);
        assertThat(r0.path("validOrderCount").asInt()).isZero();

        // 模擬一筆完整購買:Redis 扣 1、DB 扣 1、建 PENDING 訂單、寫 DEDUCT 流水
        simulatePurchase(ttId, Long.parseLong(eventId));

        JsonNode r1 = json(get("/api/v1/admin/ticket-types/" + ttId + "/reconcile", admin)).path("data");
        assertThat(r1.path("consistent").asBoolean()).isTrue();
        assertThat(r1.path("soldByDb").asInt()).isEqualTo(1);
        assertThat(r1.path("dbStockRemaining").asInt()).isEqualTo(999);
        assertThat(r1.path("redisStockRemaining").asInt()).isEqualTo(999);
        assertThat(r1.path("validOrderCount").asInt()).isEqualTo(1);
        assertThat(r1.path("stockLogNetDelta").asInt()).isEqualTo(-1);
    }

    // ---- 對帳偵測不一致:僅 Redis 漂移 ----

    @Test
    void reconcileDetectsRedisDrift() {
        String admin = createAdminToken();
        String eventId = createEvent(admin);
        String ttId = createTicketType(admin, eventId, 200);
        post("/api/v1/admin/ticket-types/" + ttId + "/warmup", admin, null);

        // 僅讓 Redis 漂移(DB 未動),對帳應標示不一致
        redisTemplate.opsForValue().increment(STOCK_KEY + ttId, -5);

        JsonNode r = json(get("/api/v1/admin/ticket-types/" + ttId + "/reconcile", admin)).path("data");
        assertThat(r.path("consistent").asBoolean()).isFalse();
        assertThat(r.path("dbStockRemaining").asInt()).isEqualTo(200);
        assertThat(r.path("redisStockRemaining").asInt()).isEqualTo(195);
    }

    @Test
    void reconcileMissingTicketTypeShouldReturn2004() {
        String admin = createAdminToken();
        ResponseEntity<String> resp = get("/api/v1/admin/ticket-types/424242/reconcile", admin);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(resp).path("code").asInt()).isEqualTo(2004);
    }

    /** 直接以 SQL 模擬一次成功落庫(M3/M4 的效果),供對帳驗證三方一致。 */
    private void simulatePurchase(long ticketTypeId, long eventId) {
        redisTemplate.opsForValue().increment(STOCK_KEY + ticketTypeId, -1);
        jdbcTemplate.update(
                "UPDATE ticket_types SET stock_remaining = stock_remaining - 1, updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), ticketTypeId);

        long userId = insertUser(Role.USER).getId();
        long orderId = idGenerator.nextId();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO orders (id, user_id, event_id, ticket_type_id, price, status, request_id, "
                        + "created_at, updated_at, expire_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                orderId, userId, eventId, ticketTypeId, new BigDecimal("1000.00"), "PENDING_PAYMENT",
                "req-" + orderId, now, now, Timestamp.from(Instant.now().plus(15, ChronoUnit.MINUTES)));
        jdbcTemplate.update(
                "INSERT INTO stock_logs (id, ticket_type_id, order_id, delta, type, created_at) "
                        + "VALUES (?,?,?,?,?,?)",
                idGenerator.nextId(), ticketTypeId, orderId, -1, "DEDUCT", now);
    }
}
