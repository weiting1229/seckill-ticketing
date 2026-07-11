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
 *
 * <p>M3 於此擴充搶購熱路徑的原子<b>扣減 / 回補</b>({@code seckill_deduct.lua} /
 * {@code seckill_revert.lua}),與預熱共用同一把 stock key,避免另建重複的庫存存取類
 * (見 ADR 0004 決策);已購去重集合為 {@code seckill:bought:{ticketTypeId}}。
 */
@Component
public class StockCache {

    private static final String KEY_PREFIX = "seckill:stock:";
    private static final String BOUGHT_KEY_PREFIX = "seckill:bought:";

    /** {@link #deduct} 回傳值(對應 seckill_deduct.lua)。 */
    public static final long DEDUCT_SUCCESS = 1L;
    public static final long DEDUCT_DUPLICATE = -1L;
    public static final long DEDUCT_SOLD_OUT = -2L;
    public static final long DEDUCT_NOT_WARMED = -3L;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> warmupScript;
    private final RedisScript<Long> deductScript;
    private final RedisScript<Long> revertScript;

    public StockCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.warmupScript = longScript("lua/stock_warmup.lua");
        this.deductScript = longScript("lua/seckill_deduct.lua");
        this.revertScript = longScript("lua/seckill_revert.lua");
    }

    private static RedisScript<Long> longScript(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(location)));
        script.setResultType(Long.class);
        return script;
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

    /**
     * 搶購原子扣減(seckill_deduct.lua):查重複購買 → 查庫存 → 扣庫存 → 記已購,整段原子執行。
     *
     * @return {@link #DEDUCT_SUCCESS} 1 成功、{@link #DEDUCT_DUPLICATE} -1 重複、
     *         {@link #DEDUCT_SOLD_OUT} -2 售罄、{@link #DEDUCT_NOT_WARMED} -3 未預熱
     */
    public long deduct(long ticketTypeId, long userId) {
        Long result = redisTemplate.execute(
                deductScript,
                List.of(key(ticketTypeId), boughtKey(ticketTypeId)),
                String.valueOf(userId));
        // Lua 必回整數;理論上不為 null
        return result == null ? DEDUCT_NOT_WARMED : result;
    }

    /**
     * 搶購原子回補(seckill_revert.lua):INCR 庫存 + SREM 已購,用於 DB 落庫失敗與超時取消。
     * 與 {@link #deduct} 對稱,恆成功。
     */
    public void revert(long ticketTypeId, long userId) {
        redisTemplate.execute(
                revertScript,
                List.of(key(ticketTypeId), boughtKey(ticketTypeId)),
                String.valueOf(userId));
    }

    private String key(long ticketTypeId) {
        return KEY_PREFIX + ticketTypeId;
    }

    private String boughtKey(long ticketTypeId) {
        return BOUGHT_KEY_PREFIX + ticketTypeId;
    }
}
