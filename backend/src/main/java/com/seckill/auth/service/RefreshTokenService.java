package com.seckill.auth.service;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * refresh token 的 Redis 存放與撤銷(設計文件第 10 節:refresh 存 Redis、可主動撤銷)。
 *
 * <p>設計:每個 userId 僅保留一個有效 refresh(key = auth:refresh:{userId},value = jti,TTL = refresh 有效期)。
 * 換發只驗證 jti 是否相符;logout 直接刪 key 即完成撤銷。
 * 取捨:同一使用者多裝置登入會互相覆蓋(後登入者使先前 refresh 失效);MVP 可接受,多裝置支援留待後續。
 */
@Service
public class RefreshTokenService {

    private static final String KEY_PREFIX = "auth:refresh:";

    private final StringRedisTemplate redisTemplate;

    public RefreshTokenService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void store(long userId, String jti, Duration ttl) {
        redisTemplate.opsForValue().set(key(userId), jti, ttl);
    }

    /** 目前存放的 jti 與傳入相符才算有效(涵蓋已撤銷、已被新登入覆蓋、已過期三種失效)。 */
    public boolean isValid(long userId, String jti) {
        String stored = redisTemplate.opsForValue().get(key(userId));
        return stored != null && stored.equals(jti);
    }

    public void revoke(long userId) {
        redisTemplate.delete(key(userId));
    }

    private String key(long userId) {
        return KEY_PREFIX + userId;
    }
}
