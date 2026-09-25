package com.seckill.seckill.ratelimit;

import com.seckill.common.metrics.SeckillMetrics;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 搶購限流(設計文件第 10 節、ADR 0010):Redis Lua 令牌桶,一次腳本呼叫原子檢查多層。
 *
 * <p>{@code /seckill/purchase} 的全域、單 IP、單用戶三層由 {@link #checkPurchase} <b>一次</b>檢查,
 * 三層都有額度才一起扣,被任一層擋下的請求不消耗其他層額度(ADR 0010 §3);
 * {@code /seckill/token} 的單用戶層由 {@link #tryTokenUser} 單獨檢查。容量即每秒速率(平滑補充)。
 *
 * <p>走 {@code RateLimitConfig} 的專用長駐連線,不走 {@code StringRedisTemplate}(後者在本專案每次呼叫都新開連線,
 * 見 {@code RateLimitConfig} 與 ADR 0010 §6)。腳本以 {@code EVALSHA} 送出,Redis 重啟後腳本快取清空時
 * 收到 NOSCRIPT 則改送一次完整 {@code EVAL}(同時重新快取)。
 *
 * <p>每次呼叫皆計時回報 {@code seckill.ratelimit.check.duration}(見 {@link SeckillMetrics}):
 * 這段耗時是從發出呼叫到拿到回應的 wall-clock 時間,天然含 Redis 單執行緒佇列排隊等待,
 * 跟 {@code SLOWLOG} 只記錄 server 端執行片段不同。
 */
@Service
public class RateLimiterService {

    // 刻意不沿用 Bucket4j 時代的 seckill:rl:*:那裡存的是字串型別,新腳本讀 hash 會 WRONGTYPE(ADR 0010 §5)
    private static final String GLOBAL_KEY = "seckill:ratelimit:global";
    private static final String IP_KEY_PREFIX = "seckill:ratelimit:ip:";
    private static final String USER_KEY_PREFIX = "seckill:ratelimit:user:";
    private static final String TOKEN_USER_KEY_PREFIX = "seckill:ratelimit:token:user:";

    private static final String SCRIPT_LOCATION = "lua/ratelimit_token_bucket.lua";

    private final RedisCommands<String, String> redis;
    private final SeckillMetrics metrics;
    private final String script;
    private final String scriptSha;
    private final String globalCapacity;
    private final String userCapacity;
    private final String ipCapacity;
    private final String tokenUserCapacity;
    private final boolean bypassEnabled;

    public RateLimiterService(
            StatefulRedisConnection<String, String> rateLimitRedisConnection,
            SeckillMetrics metrics,
            @Value("${seckill.ratelimit.global-capacity:3000}") long globalCapacity,
            @Value("${seckill.ratelimit.user-capacity:2}") long userCapacity,
            @Value("${seckill.ratelimit.ip-capacity:10}") long ipCapacity,
            @Value("${seckill.ratelimit.token-user-capacity:5}") long tokenUserCapacity,
            @Value("${seckill.ratelimit.bypass-enabled:false}") boolean bypassEnabled) {
        this.redis = rateLimitRedisConnection.sync();
        this.metrics = metrics;
        this.script = loadScript();
        this.scriptSha = redis.digest(script);
        this.globalCapacity = Long.toString(globalCapacity);
        this.userCapacity = Long.toString(userCapacity);
        this.ipCapacity = Long.toString(ipCapacity);
        this.tokenUserCapacity = Long.toString(tokenUserCapacity);
        this.bypassEnabled = bypassEnabled;
    }

    /**
     * 搶購三層:全域 → 單 IP → 單用戶({@code userId} 為 null 時略過)。
     *
     * @return 空表示放行(各層已扣 1);否則為第一個額度不足的層,且任何一層都沒有被扣
     */
    public Optional<RateLimitLayer> checkPurchase(String ip, Long userId) {
        if (bypassEnabled) {
            return Optional.empty();
        }
        List<RateLimitLayer> layers = new ArrayList<>(3);
        List<String> keys = new ArrayList<>(3);
        List<String> capacities = new ArrayList<>(3);
        layers.add(RateLimitLayer.GLOBAL);
        keys.add(GLOBAL_KEY);
        capacities.add(globalCapacity);
        layers.add(RateLimitLayer.IP);
        keys.add(IP_KEY_PREFIX + ip);
        capacities.add(ipCapacity);
        if (userId != null) {
            layers.add(RateLimitLayer.USER);
            keys.add(USER_KEY_PREFIX + userId);
            capacities.add(userCapacity);
        }
        return check("purchase", layers, keys, capacities);
    }

    /**
     * 領取 token 的單用戶速率(較寬鬆,專屬 /seckill/token)。以 userId 為 key:
     * 保護 token 端點的 DB 查詢不被單一帳號狂刷,又不與單 IP 混用(避免測試共用 localhost 互擾)。
     */
    public boolean tryTokenUser(long userId) {
        if (bypassEnabled) {
            return true;
        }
        return check("token_user", List.of(RateLimitLayer.TOKEN_USER),
                List.of(TOKEN_USER_KEY_PREFIX + userId), List.of(tokenUserCapacity)).isEmpty();
    }

    private static String loadScript() {
        try (InputStream in = new ClassPathResource(SCRIPT_LOCATION).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("讀取限流腳本失敗: " + SCRIPT_LOCATION, e);
        }
    }

    private Long runScript(String[] keys, String[] args) {
        try {
            return redis.evalsha(scriptSha, ScriptOutputType.INTEGER, keys, args);
        } catch (RedisNoScriptException e) {
            return redis.eval(script, ScriptOutputType.INTEGER, keys, args);
        }
    }

    private Optional<RateLimitLayer> check(String callSite, List<RateLimitLayer> layers,
                                           List<String> keys, List<String> capacities) {
        long start = System.nanoTime();
        Long result;
        try {
            result = runScript(keys.toArray(String[]::new), capacities.toArray(String[]::new));
        } finally {
            metrics.recordRateLimitCheckDuration(callSite, System.nanoTime() - start);
        }
        if (result == null || result < 0 || result > layers.size()) {
            // 腳本只會回 0..層數;超出範圍代表腳本與呼叫端對不上,屬程式錯誤,不可默默放行
            throw new IllegalStateException("限流腳本回傳非預期值: " + result);
        }
        return result == 0 ? Optional.empty() : Optional.of(layers.get(result.intValue() - 1));
    }
}
