package com.seckill.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.support.AbstractIntegrationTest;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 認證端到端整合測試(設計文件第 9、10 節驗收場景)。
 */
class AuthFlowIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "Password123";
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ObjectMapper objectMapper;

    private String uniqueUsername() {
        return "user_" + SEQ.incrementAndGet() + "_" + System.nanoTime();
    }

    private ResponseEntity<String> post(String path, Object body) {
        return restTemplate.postForEntity(path, body, String.class);
    }

    private ResponseEntity<String> getWithToken(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    // ---- 完整快樂路徑 + 撤銷 ----

    @Test
    void registerLoginAccessRefreshThenRevoke() throws Exception {
        String username = uniqueUsername();

        // 註冊
        ResponseEntity<String> register = post("/api/v1/auth/register",
                Map.of("username", username, "password", PASSWORD));
        assertThat(register.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(register).path("code").asInt()).isZero();

        // 登入
        ResponseEntity<String> login = post("/api/v1/auth/login",
                Map.of("username", username, "password", PASSWORD));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = json(login).path("data");
        String accessToken = data.path("accessToken").asText();
        String refreshToken = data.path("refreshToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();

        // 帶 token 存取受保護資源
        ResponseEntity<String> me = getWithToken("/api/v1/auth/me", accessToken);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(me).path("data").path("username").asText()).isEqualTo(username);

        // refresh 換發新 access token
        ResponseEntity<String> refreshed = post("/api/v1/auth/refresh",
                Map.of("refreshToken", refreshToken));
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(refreshed).path("data").path("accessToken").asText()).isNotBlank();

        // 登出撤銷
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> logout = restTemplate.exchange(
                "/api/v1/auth/logout", HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 撤銷後 refresh 失敗
        ResponseEntity<String> afterRevoke = post("/api/v1/auth/refresh",
                Map.of("refreshToken", refreshToken));
        assertThat(afterRevoke.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(afterRevoke).path("code").asInt()).isEqualTo(1005);
    }

    // ---- 註冊 / 登入的錯誤路徑 ----

    @Test
    void duplicateUsernameShouldReturn1001() throws Exception {
        String username = uniqueUsername();
        post("/api/v1/auth/register", Map.of("username", username, "password", PASSWORD));

        ResponseEntity<String> again = post("/api/v1/auth/register",
                Map.of("username", username, "password", PASSWORD));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(again).path("code").asInt()).isEqualTo(1001);
    }

    @Test
    void wrongPasswordShouldReturn1002() throws Exception {
        String username = uniqueUsername();
        post("/api/v1/auth/register", Map.of("username", username, "password", PASSWORD));

        ResponseEntity<String> login = post("/api/v1/auth/login",
                Map.of("username", username, "password", "WrongPass999"));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(login).path("code").asInt()).isEqualTo(1002);
    }

    @Test
    void weakPasswordShouldFailValidationWith1400() throws Exception {
        ResponseEntity<String> register = post("/api/v1/auth/register",
                Map.of("username", uniqueUsername(), "password", "short"));
        assertThat(register.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(register).path("code").asInt()).isEqualTo(1400);
    }

    // ---- 三種 401 場景 ----

    @Test
    void noTokenShouldReturn401With1401() throws Exception {
        ResponseEntity<String> me = restTemplate.getForEntity("/api/v1/auth/me", String.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(me).path("code").asInt()).isEqualTo(1401);
    }

    @Test
    void expiredTokenShouldReturn401() {
        ResponseEntity<String> me = getWithToken("/api/v1/auth/me", craftExpiredAccessToken());
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tamperedTokenShouldReturn401() throws Exception {
        String username = uniqueUsername();
        post("/api/v1/auth/register", Map.of("username", username, "password", PASSWORD));
        ResponseEntity<String> login = post("/api/v1/auth/login",
                Map.of("username", username, "password", PASSWORD));
        String valid = json(login).path("data").path("accessToken").asText();
        String tampered = valid.substring(0, valid.length() - 1) + (valid.endsWith("a") ? "b" : "a");

        ResponseEntity<String> me = getWithToken("/api/v1/auth/me", tampered);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** 以相同測試祕密簽發一個已過期的 access token。 */
    private String craftExpiredAccessToken() {
        SecretKey key = Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("999")
                .claim("username", "ghost")
                .claim("role", "USER")
                .claim("type", "access")
                .issuedAt(Date.from(now.minusSeconds(120)))
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
