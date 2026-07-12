package com.seckill.seckill;

import static org.assertj.core.api.Assertions.assertThat;

import com.seckill.seckill.ratelimit.RateLimiterService;
import com.seckill.support.AbstractIntegrationTest;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 搶購三層限流(子項 C)整合測試:直接呼叫 {@link RateLimiterService},驗證各層邊界與 key 隔離。
 * 全域容量於此縮為 4 以便確定性驗證(單用戶維持 2、單 IP 維持 10)。
 */
@TestPropertySource(properties = "seckill.ratelimit.global-capacity=4")
class RateLimiterServiceIT extends AbstractIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    RateLimiterService rateLimiter;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void globalAllowsUpToCapacityThenBlocks() {
        redisTemplate.delete("seckill:rl:global"); // 重置桶,確定性驗證
        for (int i = 0; i < 4; i++) {
            assertThat(rateLimiter.tryGlobal()).as("第 %d 次應放行", i + 1).isTrue();
        }
        assertThat(rateLimiter.tryGlobal()).as("第 5 次應被限流").isFalse();
    }

    @Test
    void userAllowsTwoPerSecondThenBlocks() {
        long userA = SEQ.incrementAndGet();
        long userB = SEQ.incrementAndGet();

        assertThat(rateLimiter.tryUser(userA)).isTrue();
        assertThat(rateLimiter.tryUser(userA)).isTrue();
        assertThat(rateLimiter.tryUser(userA)).as("同一用戶第 3 次應被限流").isFalse();

        // 不同用戶各自獨立,不受 A 影響
        assertThat(rateLimiter.tryUser(userB)).isTrue();
    }

    @Test
    void tokenUserAllowsFivePerSecondThenBlocks() {
        long userA = SEQ.incrementAndGet();
        long userB = SEQ.incrementAndGet();

        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiter.tryTokenUser(userA)).as("領 token 第 %d 次應放行", i + 1).isTrue();
        }
        assertThat(rateLimiter.tryTokenUser(userA)).as("領 token 第 6 次應被限流").isFalse();

        // 不同用戶各自獨立
        assertThat(rateLimiter.tryTokenUser(userB)).isTrue();
    }

    @Test
    void ipAllowsTenPerSecondThenBlocks() {
        String ipA = "10.0.0." + SEQ.incrementAndGet();
        String ipB = "10.0.0." + SEQ.incrementAndGet();

        for (int i = 0; i < 10; i++) {
            assertThat(rateLimiter.tryIp(ipA)).as("IP 第 %d 次應放行", i + 1).isTrue();
        }
        assertThat(rateLimiter.tryIp(ipA)).as("IP 第 11 次應被限流").isFalse();

        // 不同 IP 各自獨立
        assertThat(rateLimiter.tryIp(ipB)).isTrue();
    }
}
