package com.seckill.seckill;

import static org.assertj.core.api.Assertions.assertThat;

import com.seckill.auth.domain.Role;
import com.seckill.seckill.service.SeckillTokenService;
import com.seckill.support.AbstractAdminIntegrationTest;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 一次性搶購 token(子項 B)整合測試:領取的時間窗 / 上線 / 存在性守衛(3xxx),
 * 以及 token 原子一次性消耗(Lua GET+DEL)與併發只一個通過。
 */
class SeckillTokenIT extends AbstractAdminIntegrationTest {

    private static final String TOKEN_KEY = "seckill:token:";

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    SeckillTokenService tokenService;

    private String createEvent(String admin) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", "token 測試");
        m.put("venue", "Taipei");
        m.put("eventTime", Instant.now().plus(10, ChronoUnit.DAYS).toString());
        return json(post("/api/v1/admin/events", admin, m)).path("data").path("id").asText();
    }

    /** 建立票種,可自訂開賣時間窗。 */
    private String createTicketType(String admin, String eventId, Instant start, Instant end) {
        Map<String, Object> m = new HashMap<>();
        m.put("eventId", eventId);
        m.put("name", "看台");
        m.put("price", "1000.00");
        m.put("totalStock", 100);
        m.put("seckillStart", start.toString());
        m.put("seckillEnd", end.toString());
        return json(post("/api/v1/admin/ticket-types", admin, m)).path("data").path("id").asText();
    }

    private void warmup(String admin, String ttId) {
        post("/api/v1/admin/ticket-types/" + ttId + "/warmup", admin, null);
    }

    private ResponseEntity<String> requestToken(String userToken, String ttId) {
        return post("/api/v1/seckill/token", userToken, Map.of("ticketTypeId", Long.parseLong(ttId)));
    }

    // ---- 領取成功:上線且在時間窗內 ----

    @Test
    void issueTokenSucceedsWithinWindow() {
        String admin = createAdminToken();
        String user = createUserToken();
        String eventId = createEvent(admin);
        String ttId = createTicketType(admin, eventId,
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.DAYS));
        warmup(admin, ttId);

        ResponseEntity<String> resp = requestToken(user, ttId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var data = json(resp).path("data");
        assertThat(data.path("token").asText()).isNotBlank();
        assertThat(data.path("ticketTypeId").asText()).isEqualTo(ttId);
        assertThat(data.path("expiresInSeconds").asLong()).isEqualTo(60);
    }

    // ---- 時間窗守衛 ----

    @Test
    void issueTokenBeforeStartReturns3001() {
        String admin = createAdminToken();
        String user = createUserToken();
        String eventId = createEvent(admin);
        String ttId = createTicketType(admin, eventId,
                Instant.now().plus(1, ChronoUnit.HOURS), Instant.now().plus(2, ChronoUnit.HOURS));
        warmup(admin, ttId);

        ResponseEntity<String> resp = requestToken(user, ttId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(resp).path("code").asInt()).isEqualTo(3001);
    }

    @Test
    void issueTokenAfterEndReturns3002() {
        String admin = createAdminToken();
        String user = createUserToken();
        String eventId = createEvent(admin);
        String ttId = createTicketType(admin, eventId,
                Instant.now().minus(2, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.DAYS));
        warmup(admin, ttId);

        ResponseEntity<String> resp = requestToken(user, ttId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(resp).path("code").asInt()).isEqualTo(3002);
    }

    // ---- 未上線(未預熱)----

    @Test
    void issueTokenNotOnlineReturns3003() {
        String admin = createAdminToken();
        String user = createUserToken();
        String eventId = createEvent(admin);
        String ttId = createTicketType(admin, eventId,
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.DAYS));
        // 刻意不 warmup(OFFLINE)

        ResponseEntity<String> resp = requestToken(user, ttId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(resp).path("code").asInt()).isEqualTo(3003);
    }

    // ---- 票種不存在 ----

    @Test
    void issueTokenMissingTicketTypeReturns2004() {
        String user = createUserToken();
        ResponseEntity<String> resp = requestToken(user, "424242");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(resp).path("code").asInt()).isEqualTo(2004);
    }

    // ---- 未認證 ----

    @Test
    void issueTokenUnauthenticatedReturns401() {
        ResponseEntity<String> resp = requestToken(null, "1");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- 領取後 Redis 只存雜湊(非明文)且帶約 60s TTL ----

    @Test
    void issuedTokenStoredAsHashWithTtl() {
        String admin = createAdminToken();
        String eventId = createEvent(admin);
        String ttId = createTicketType(admin, eventId,
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.DAYS));
        warmup(admin, ttId);

        long userId = insertUser(Role.USER).getId();
        long tt = Long.parseLong(ttId);
        String token = tokenService.issue(userId, tt);

        String key = TOKEN_KEY + userId + ":" + ttId;
        String stored = redisTemplate.opsForValue().get(key);
        // Redis 內為雜湊,不得等於明文 token(防禦縱深)
        assertThat(stored).isNotBlank().isNotEqualTo(token);
        Long ttl = redisTemplate.getExpire(key);
        assertThat(ttl).isNotNull();
        assertThat(ttl).isBetween(1L, 60L);
        // 明文仍可通過校驗(後端雜湊後比對)
        assertThat(tokenService.consume(userId, tt, token)).isTrue();
    }

    // ---- 防舞弊:ADMIN 不得領搶購 token(URL + 方法層限 ROLE_USER)----

    @Test
    void adminForbiddenFromSeckill() {
        String admin = createAdminToken();
        String eventId = createEvent(admin);
        String ttId = createTicketType(admin, eventId,
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.DAYS));
        warmup(admin, ttId);

        ResponseEntity<String> resp = requestToken(admin, ttId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- 原子一次性消耗:第二次失敗、錯誤 token 失敗 ----

    @Test
    void consumeIsOneTimeAndRejectsWrongToken() {
        String admin = createAdminToken();
        String eventId = createEvent(admin);
        String ttId = createTicketType(admin, eventId,
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.DAYS));
        warmup(admin, ttId);

        long userId = insertUser(Role.USER).getId();
        long tt = Long.parseLong(ttId);
        String token = tokenService.issue(userId, tt);

        assertThat(tokenService.consume(userId, tt, "wrong-token")).isFalse();
        assertThat(tokenService.consume(userId, tt, token)).isTrue();   // 首次通過並消耗
        assertThat(tokenService.consume(userId, tt, token)).isFalse();  // 已焚,再用失敗
    }

    // ---- token 端點限流:單一帳號快打會出現 429(以 userId 為 key,不與其他測試互擾)----

    @Test
    void tokenEndpointRateLimitsPerUser() {
        String admin = createAdminToken();
        String user = createUserToken();
        String eventId = createEvent(admin);
        String ttId = createTicketType(admin, eventId,
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.DAYS));
        warmup(admin, ttId);

        // 容量 5/s,連打 20 次(遠超補充速度)必出現至少一次 429;首次應為 200
        boolean sawOk = false;
        boolean saw429 = false;
        for (int i = 0; i < 20; i++) {
            HttpStatus status = (HttpStatus) requestToken(user, ttId).getStatusCode();
            if (status == HttpStatus.OK) {
                sawOk = true;
            } else if (status == HttpStatus.TOO_MANY_REQUESTS) {
                saw429 = true;
            }
        }
        assertThat(sawOk).as("應有成功的領取").isTrue();
        assertThat(saw429).as("快打應觸發 429 限流").isTrue();
    }

    // ---- 併發消耗同一 token:恰一個通過 ----

    @Test
    void concurrentConsumeOnlyOneSucceeds() throws Exception {
        String admin = createAdminToken();
        String eventId = createEvent(admin);
        String ttId = createTicketType(admin, eventId,
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.DAYS));
        warmup(admin, ttId);

        long userId = insertUser(Role.USER).getId();
        long tt = Long.parseLong(ttId);
        String token = tokenService.issue(userId, tt);

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Boolean>> tasks = java.util.Collections.nCopies(threads,
                    () -> tokenService.consume(userId, tt, token));
            List<Future<Boolean>> futures = pool.invokeAll(tasks);
            long passed = 0;
            for (Future<Boolean> f : futures) {
                if (f.get()) {
                    passed++;
                }
            }
            assertThat(passed).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }
}
