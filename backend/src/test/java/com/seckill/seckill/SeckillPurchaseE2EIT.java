package com.seckill.seckill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.seckill.support.AbstractAdminIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 搶購全流程(子項 F)端到端整合測試:token → purchase → 非同步落庫 → 輪詢結果 → M2 對帳。
 * 全域/單 IP 限流於此提高,使併發請求都能抵達 deduct(單獨的 429 驗證見 SeckillPurchaseRateLimitIT)。
 */
@TestPropertySource(properties = {
        "seckill.ratelimit.ip-capacity=5000",
        "seckill.ratelimit.global-capacity=10000"
})
class SeckillPurchaseE2EIT extends AbstractAdminIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    /**
     * 重置本測試依賴的限流桶。全域桶 key 固定,跨 context 共用同一把 Redis key,
     * 其他測試類別(不同閾值)會殘留狀態;刪除後以本 context 閾值重建,確保併發請求都能抵達 deduct。
     */
    private void resetRateLimitBuckets() {
        redisTemplate.delete("seckill:rl:global");
        redisTemplate.delete("seckill:rl:ip:127.0.0.1");
        redisTemplate.delete("seckill:rl:ip:0:0:0:0:0:0:0:1");
    }

    private String createEvent(String admin) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", "搶購 e2e");
        m.put("venue", "Taipei");
        m.put("eventTime", Instant.now().plus(10, ChronoUnit.DAYS).toString());
        return json(post("/api/v1/admin/events", admin, m)).path("data").path("id").asText();
    }

    private long createTicketType(String admin, String eventId, int totalStock) {
        Map<String, Object> m = new HashMap<>();
        m.put("eventId", eventId);
        m.put("name", "看台");
        m.put("price", "1500.00");
        m.put("totalStock", totalStock);
        m.put("seckillStart", Instant.now().minus(1, ChronoUnit.HOURS).toString());
        m.put("seckillEnd", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        return Long.parseLong(json(post("/api/v1/admin/ticket-types", admin, m)).path("data").path("id").asText());
    }

    private void warmup(String admin, long ttId) {
        post("/api/v1/admin/ticket-types/" + ttId + "/warmup", admin, null);
    }

    private String issueToken(String userToken, long ttId) {
        return json(post("/api/v1/seckill/token", userToken, Map.of("ticketTypeId", ttId)))
                .path("data").path("token").asText();
    }

    private ResponseEntity<String> purchase(String userToken, long ttId, String seckillToken) {
        return post("/api/v1/seckill/purchase", userToken,
                Map.of("ticketTypeId", ttId, "token", seckillToken));
    }

    private String pollStatus(String userToken, String requestId) {
        return json(get("/api/v1/seckill/result/" + requestId, userToken)).path("data").path("status").asText();
    }

    private int orderCount(long ttId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE ticket_type_id = ?", Integer.class, ttId);
    }

    // ---- (a) 正常搶購 end-to-end + 對帳三方一致 ----

    @Test
    void normalPurchaseEndToEndReconciles() {
        String admin = createAdminToken();
        long ttId = createTicketType(admin, createEvent(admin), 100);
        warmup(admin, ttId);
        String user = createUserToken();

        ResponseEntity<String> resp = purchase(user, ttId, issueToken(user, ttId));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = json(resp).path("data");
        String requestId = data.path("requestId").asText();
        String orderId = data.path("orderId").asText();
        assertThat(requestId).isNotBlank();
        assertThat(orderId).isNotBlank();

        await().atMost(Duration.ofSeconds(15)).until(() -> "SUCCESS".equals(pollStatus(user, requestId)));
        assertThat(json(get("/api/v1/seckill/result/" + requestId, user)).path("data").path("orderId").asText())
                .isEqualTo(orderId);

        JsonNode r = json(get("/api/v1/admin/ticket-types/" + ttId + "/reconcile", admin)).path("data");
        assertThat(r.path("consistent").asBoolean()).isTrue();
        assertThat(r.path("soldByDb").asInt()).isEqualTo(1);
        assertThat(r.path("dbStockRemaining").asInt()).isEqualTo(99);
        assertThat(r.path("redisStockRemaining").asInt()).isEqualTo(99);
        assertThat(r.path("validOrderCount").asInt()).isEqualTo(1);
    }

    // ---- (b) 庫存 10、50 執行緒併發 → 恰 10 筆訂單、零超賣、對帳一致 ----

    @Test
    void concurrentPurchaseNeverOversells() throws Exception {
        String admin = createAdminToken();
        int stock = 10;
        int threads = 50;
        long ttId = createTicketType(admin, createEvent(admin), stock);
        warmup(admin, ttId);
        resetRateLimitBuckets(); // 排除其他測試類別殘留的限流桶狀態

        // 預備 50 組 (accessToken, seckillToken)
        List<String[]> creds = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String u = createUserToken();
            creds.add(new String[]{u, issueToken(u, ttId)});
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> tasks = creds.stream()
                    .<Callable<Integer>>map(c -> () -> purchase(c[0], ttId, c[1]).getStatusCode().value())
                    .toList();
            List<Future<Integer>> futures = pool.invokeAll(tasks);
            long ok = 0;
            for (Future<Integer> f : futures) {
                if (f.get() == 200) {
                    ok++;
                }
            }
            // 恰 stock 筆成功,零超賣
            assertThat(ok).isEqualTo(stock);
        } finally {
            pool.shutdown();
        }

        // 等消費者把 10 筆落庫,再對帳
        await().atMost(Duration.ofSeconds(20)).until(() -> orderCount(ttId) == stock);
        JsonNode r = json(get("/api/v1/admin/ticket-types/" + ttId + "/reconcile", admin)).path("data");
        assertThat(r.path("consistent").asBoolean()).isTrue();
        assertThat(r.path("dbStockRemaining").asInt()).isZero();
        assertThat(r.path("redisStockRemaining").asInt()).isZero();
        assertThat(r.path("validOrderCount").asInt()).isEqualTo(stock);
    }

    // ---- (c) 同一用戶連搶兩次 → 第二次被拒(3006)、僅一筆訂單 ----

    @Test
    void duplicatePurchaseBySameUserRejected() {
        String admin = createAdminToken();
        long ttId = createTicketType(admin, createEvent(admin), 100);
        warmup(admin, ttId);
        String user = createUserToken();

        ResponseEntity<String> first = purchase(user, ttId, issueToken(user, ttId));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 已購:再領 token 再搶 → deduct 回 -1 → 3006
        ResponseEntity<String> second = purchase(user, ttId, issueToken(user, ttId));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(second).path("code").asInt()).isEqualTo(3006);

        String requestId = json(first).path("data").path("requestId").asText();
        await().atMost(Duration.ofSeconds(15)).until(() -> "SUCCESS".equals(pollStatus(user, requestId)));
        assertThat(orderCount(ttId)).isEqualTo(1);
    }

    // ---- 無效 token → 3007 ----

    @Test
    void invalidTokenRejected() {
        String admin = createAdminToken();
        long ttId = createTicketType(admin, createEvent(admin), 100);
        warmup(admin, ttId);
        String user = createUserToken();

        ResponseEntity<String> resp = purchase(user, ttId, "bogus-token");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(resp).path("code").asInt()).isEqualTo(3007);
    }

    // ---- 售罄 → 3005 ----

    @Test
    void soldOutRejected() {
        String admin = createAdminToken();
        long ttId = createTicketType(admin, createEvent(admin), 1);
        warmup(admin, ttId);

        String buyer = createUserToken();
        assertThat(purchase(buyer, ttId, issueToken(buyer, ttId)).getStatusCode()).isEqualTo(HttpStatus.OK);

        String late = createUserToken();
        ResponseEntity<String> resp = purchase(late, ttId, issueToken(late, ttId));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(resp).path("code").asInt()).isEqualTo(3005);
    }
}
