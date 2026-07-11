package com.seckill.seckill.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 搶購三層限流(設計文件第 10 節):全域 QPS、單用戶對 /seckill/purchase、單 IP,各自獨立 token bucket。
 *
 * <p>桶狀態存於 Redis(Bucket4j Lettuce),各層以不同 key 隔離;容量即每秒速率(greedy 平滑補充)。
 * 三個 {@code try*} 方法互不相干,呼叫端可依序短路檢查(任一被限流即拒絕)。
 */
@Service
public class RateLimiterService {

    private static final String GLOBAL_KEY = "seckill:rl:global";
    private static final String USER_KEY_PREFIX = "seckill:rl:user:";
    private static final String IP_KEY_PREFIX = "seckill:rl:ip:";

    private final ProxyManager<String> proxyManager;
    private final BucketConfiguration globalConfig;
    private final BucketConfiguration userConfig;
    private final BucketConfiguration ipConfig;

    public RateLimiterService(
            ProxyManager<String> proxyManager,
            @Value("${seckill.ratelimit.global-capacity:3000}") long globalCapacity,
            @Value("${seckill.ratelimit.user-capacity:2}") long userCapacity,
            @Value("${seckill.ratelimit.ip-capacity:10}") long ipCapacity) {
        this.proxyManager = proxyManager;
        this.globalConfig = perSecond(globalCapacity);
        this.userConfig = perSecond(userCapacity);
        this.ipConfig = perSecond(ipCapacity);
    }

    /** capacity 個 token,每秒 greedy 補滿(平滑速率,避免整秒邊界爆量)。 */
    private static BucketConfiguration perSecond(long capacity) {
        return BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, Duration.ofSeconds(1)))
                .build();
    }

    /** 全域 QPS。true 表放行、false 表已達上限。 */
    public boolean tryGlobal() {
        return proxyManager.getProxy(GLOBAL_KEY, () -> globalConfig).tryConsume(1);
    }

    /** 單用戶速率。 */
    public boolean tryUser(long userId) {
        return proxyManager.getProxy(USER_KEY_PREFIX + userId, () -> userConfig).tryConsume(1);
    }

    /** 單 IP 速率。 */
    public boolean tryIp(String ip) {
        return proxyManager.getProxy(IP_KEY_PREFIX + ip, () -> ipConfig).tryConsume(1);
    }
}
