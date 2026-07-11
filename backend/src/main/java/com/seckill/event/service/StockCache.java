package com.seckill.event.service;

import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

/**
 * 搶購 Redis 預扣庫存的快取存取(key = {@code seckill:stock:{ticketTypeId}})。
 *
 * <p>設計文件第 6 節:此 key 由 admin 預熱 API 寫入,搶購熱路徑(M3)於此扣減。
 * 預熱以 Lua 原子執行,保證<b>冪等且不覆蓋已扣減庫存</b>(見 {@code stock_warmup.lua})。
 * 多步驟臨界區一律 Lua(CLAUDE.md),不在 Java 端拆成多個 Redis 命令。
 */
@Component
public class StockCache {

    private static final String KEY_PREFIX = "seckill:stock:";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> warmupScript;

    public StockCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/stock_warmup.lua")));
        script.setResultType(Long.class);
        this.warmupScript = script;
    }

    /**
     * 冪等預熱:key 不存在時以 {@code stock} 建立並設定 TTL;已存在則保留現值(不覆蓋)。
     *
     * @return 預熱後 Redis 現值
     */
    public long warmup(long ticketTypeId, int stock, long ttlSeconds) {
        Long result = redisTemplate.execute(
                warmupScript,
                List.of(key(ticketTypeId)),
                String.valueOf(stock), String.valueOf(ttlSeconds));
        // Lua 必回整數;理論上不為 null
        return result == null ? stock : result;
    }

    /** 讀取 Redis 預扣庫存現值;未預熱或已過期時回 {@code null}。 */
    public Integer getStock(long ticketTypeId) {
        String value = redisTemplate.opsForValue().get(key(ticketTypeId));
        return value == null ? null : Integer.valueOf(value);
    }

    private String key(long ticketTypeId) {
        return KEY_PREFIX + ticketTypeId;
    }
}
