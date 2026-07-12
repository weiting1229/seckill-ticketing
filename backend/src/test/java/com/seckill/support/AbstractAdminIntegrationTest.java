package com.seckill.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.auth.domain.Role;
import com.seckill.auth.domain.User;
import com.seckill.auth.domain.UserStatus;
import com.seckill.auth.mapper.UserMapper;
import com.seckill.common.id.IdGenerator;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * admin 相關整合測試共用基底:提供直接建立 USER / ADMIN 帳號並登入取得 access token 的能力。
 *
 * <p>ADMIN 種子決策(ADR 0003):正式環境以環境變數驅動的 {@code AdminBootstrap} 建立;
 * 測試則直接以 {@link UserMapper} 插入指定角色帳號(不依賴提交式憑證),再走登入 API 換 token。
 */
public abstract class AbstractAdminIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Password123";
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserMapper userMapper;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected IdGenerator idGenerator;

    /** 建立一個 ADMIN 帳號並回傳其 access token。 */
    protected String createAdminToken() {
        return createUserWithRole(Role.ADMIN);
    }

    /** 建立一個一般 USER 帳號並回傳其 access token。 */
    protected String createUserToken() {
        return createUserWithRole(Role.USER);
    }

    /** 直接插入一個指定角色的 ACTIVE 帳號並回傳(供需要 user id 的測試,如訂單 FK)。 */
    protected User insertUser(Role role) {
        String username = "it_" + role.name().toLowerCase() + "_" + SEQ.incrementAndGet()
                + "_" + System.nanoTime();
        Instant now = Instant.now();
        User user = new User();
        user.setId(idGenerator.nextId());
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        return user;
    }

    /** 以指定帳號登入取得 access token;供需要同時知道 userId(insertUser 回傳)與 token 的訂單測試。 */
    protected String tokenFor(User user) {
        ResponseEntity<String> login = restTemplate.postForEntity("/api/v1/auth/login",
                Map.of("username", user.getUsername(), "password", PASSWORD), String.class);
        return json(login).path("data").path("accessToken").asText();
    }

    private String createUserWithRole(Role role) {
        return tokenFor(insertUser(role));
    }

    // ---- HTTP 輔助 ----

    protected ResponseEntity<String> exchange(String path, HttpMethod method, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<Object> entity = body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        return restTemplate.exchange(path, method, entity, String.class);
    }

    protected ResponseEntity<String> get(String path, String token) {
        return exchange(path, HttpMethod.GET, token, null);
    }

    protected ResponseEntity<String> post(String path, String token, Object body) {
        return exchange(path, HttpMethod.POST, token, body);
    }

    protected ResponseEntity<String> put(String path, String token, Object body) {
        return exchange(path, HttpMethod.PUT, token, body);
    }

    protected ResponseEntity<String> delete(String path, String token) {
        return exchange(path, HttpMethod.DELETE, token, null);
    }

    protected JsonNode json(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("回應 JSON 解析失敗:" + response.getBody(), e);
        }
    }
}
