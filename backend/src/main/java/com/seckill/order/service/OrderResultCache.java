package com.seckill.order.service;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 搶購排隊結果快取(設計文件第 6 節,key = {@code seckill:result:{requestId}},10 分鐘 TTL)。
 *
 * <p>建單消費者落庫成功寫 {@code SUCCESS:orderId};DB 扣減失敗寫 {@code FAIL:原因}。
 * 輪詢 API(子項 F)讀此 key:無 key = QUEUING、有 key 則解析 SUCCESS / FAIL。
 * 置於 order 模組(由消費者寫入),供 seckill 的輪詢端點讀取,避免 seckill↔order 循環依賴。
 */
@Component
public class OrderResultCache {

    private static final String KEY_PREFIX = "seckill:result:";
    private static final Duration TTL = Duration.ofMinutes(10);
    public static final String SUCCESS_PREFIX = "SUCCESS:";
    public static final String FAIL_PREFIX = "FAIL:";

    private final StringRedisTemplate redisTemplate;

    public OrderResultCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void writeSuccess(String requestId, long orderId) {
        redisTemplate.opsForValue().set(key(requestId), SUCCESS_PREFIX + orderId, TTL);
    }

    public void writeFail(String requestId, String reason) {
        redisTemplate.opsForValue().set(key(requestId), FAIL_PREFIX + reason, TTL);
    }

    /** 原始結果字串;無 key(仍在排隊)時回 {@code null}。 */
    public String get(String requestId) {
        return redisTemplate.opsForValue().get(key(requestId));
    }

    private String key(String requestId) {
        return KEY_PREFIX + requestId;
    }
}
