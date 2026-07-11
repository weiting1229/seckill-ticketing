package com.seckill.auth.security;

import com.seckill.auth.domain.User;
import com.seckill.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

/**
 * JWT 簽發與解析(HS256)。access / refresh 皆帶 type claim 以防混用;
 * refresh 另帶 jti(UUID)供 Redis 撤銷比對。
 * 解析失敗(過期、篡改、格式錯誤)一律拋 io.jsonwebtoken.JwtException,由呼叫端決定處理。
 */
@Service
public class JwtService {

    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_ROLE = "role";
    static final String TYPE_ACCESS = "access";
    static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public JwtService(JwtProperties properties) {
        // 少於 256 bit 會由 Keys.hmacShaKeyFor 拋 WeakKeyException,強制祕密足夠強
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTtlSeconds = properties.accessTtl().getSeconds();
        this.refreshTtlSeconds = properties.refreshTtl().getSeconds();
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlSeconds)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 產生 refresh token,回傳 token 字串與其 jti(供存入 Redis)。 */
    public GeneratedRefreshToken generateRefreshToken(User user) {
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .id(jti)
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(refreshTtlSeconds)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new GeneratedRefreshToken(token, jti);
    }

    /** 解析並驗簽;失敗拋 JwtException。 */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isAccess(Claims claims) {
        return TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public boolean isRefresh(Claims claims) {
        return TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public String usernameOf(Claims claims) {
        return claims.get(CLAIM_USERNAME, String.class);
    }

    public String roleOf(Claims claims) {
        return claims.get(CLAIM_ROLE, String.class);
    }

    public long accessTtlSeconds() {
        return accessTtlSeconds;
    }

    public long refreshTtlSeconds() {
        return refreshTtlSeconds;
    }

    public record GeneratedRefreshToken(String token, String jti) {
    }
}
