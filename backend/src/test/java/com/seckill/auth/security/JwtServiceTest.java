package com.seckill.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seckill.auth.domain.Role;
import com.seckill.auth.domain.User;
import com.seckill.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789-abcdefghijklmnopqrstuvwxyz";

    private User sampleUser() {
        User user = new User();
        user.setId(123456789L);
        user.setUsername("alice");
        user.setRole(Role.USER);
        return user;
    }

    private JwtService service(Duration accessTtl, Duration refreshTtl) {
        return new JwtService(new JwtProperties(SECRET, accessTtl, refreshTtl));
    }

    @Test
    void accessTokenShouldCarryClaimsAndBeMarkedAsAccess() {
        JwtService jwt = service(Duration.ofMinutes(15), Duration.ofDays(7));
        Claims claims = jwt.parse(jwt.generateAccessToken(sampleUser()));

        assertThat(claims.getSubject()).isEqualTo("123456789");
        assertThat(jwt.usernameOf(claims)).isEqualTo("alice");
        assertThat(jwt.roleOf(claims)).isEqualTo("USER");
        assertThat(jwt.isAccess(claims)).isTrue();
        assertThat(jwt.isRefresh(claims)).isFalse();
    }

    @Test
    void refreshTokenShouldCarryJtiAndBeMarkedAsRefresh() {
        JwtService jwt = service(Duration.ofMinutes(15), Duration.ofDays(7));
        JwtService.GeneratedRefreshToken refresh = jwt.generateRefreshToken(sampleUser());
        Claims claims = jwt.parse(refresh.token());

        assertThat(jwt.isRefresh(claims)).isTrue();
        assertThat(claims.getId()).isEqualTo(refresh.jti());
    }

    @Test
    void tamperedTokenShouldFailVerification() {
        JwtService jwt = service(Duration.ofMinutes(15), Duration.ofDays(7));
        String token = jwt.generateAccessToken(sampleUser());
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("a") ? "b" : "a");

        assertThatThrownBy(() -> jwt.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenShouldThrowExpiredException() {
        JwtService jwt = service(Duration.ofSeconds(-10), Duration.ofDays(7));  // 已過期
        String token = jwt.generateAccessToken(sampleUser());

        assertThatThrownBy(() -> jwt.parse(token)).isInstanceOf(ExpiredJwtException.class);
    }
}
