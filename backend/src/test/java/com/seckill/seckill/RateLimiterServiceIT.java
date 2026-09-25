package com.seckill.seckill;

import static org.assertj.core.api.Assertions.assertThat;

import com.seckill.seckill.ratelimit.RateLimitLayer;
import com.seckill.seckill.ratelimit.RateLimiterService;
import com.seckill.support.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 限流(ADR 0010)整合測試:直接呼叫 {@link RateLimiterService},驗證各層邊界、key 隔離,
 * 以及搶購三層的<b>全有或全無</b>(被擋下的請求不消耗其他層額度)。
 *
 * <p>全域容量縮為 {@value #GLOBAL} 以便確定性驗證(單用戶維持 2、單 IP 維持 10、token 單用戶 5)。
 * 補充只會讓 token 變多,所以「至少放行 N 次」與「超過容量就擋」的斷言不會因測試執行時間而不穩;
 * 需要「不多放」的斷言一律把經過時間的補充量算進上限。
 */
@TestPropertySource(properties = "seckill.ratelimit.global-capacity=" + RateLimiterServiceIT.GLOBAL)
class RateLimiterServiceIT extends AbstractIntegrationTest {

    static final int GLOBAL = 20;
    private static final String GLOBAL_KEY = "seckill:ratelimit:global";
    private static final AtomicLong SEQ = new AtomicLong(1_000_000);

    @Autowired
    RateLimiterService rateLimiter;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void resetGlobalBucket() {
        redisTemplate.delete(GLOBAL_KEY);
    }

    /** 每次都換新的 IP 與用戶,讓全域成為唯一可能擋下的層。 */
    private Optional<RateLimitLayer> freshPurchase() {
        long n = SEQ.incrementAndGet();
        return rateLimiter.checkPurchase("10.1." + (n / 256 % 256) + "." + (n % 256), n);
    }

    @Test
    void globalAllowsUpToCapacityThenBlocks() {
        for (int i = 0; i < GLOBAL; i++) {
            assertThat(freshPurchase()).as("第 %d 次應放行", i + 1).isEmpty();
        }
        assertThat(freshPurchase()).as("超過全域容量應被全域層擋下").contains(RateLimitLayer.GLOBAL);
    }

    @Test
    void userAllowsTwoPerSecondThenBlocks() {
        long userA = SEQ.incrementAndGet();
        long userB = SEQ.incrementAndGet();
        String ip = "10.2.0." + (userA % 256);

        assertThat(rateLimiter.checkPurchase(ip, userA)).isEmpty();
        assertThat(rateLimiter.checkPurchase(ip, userA)).isEmpty();
        assertThat(rateLimiter.checkPurchase(ip, userA)).as("同一用戶第 3 次應被單用戶層擋下")
                .contains(RateLimitLayer.USER);

        // 不同用戶各自獨立,不受 A 影響
        assertThat(rateLimiter.checkPurchase(ip, userB)).isEmpty();
    }

    @Test
    void ipAllowsTenPerSecondThenBlocks() {
        String ipA = "10.3.0." + (SEQ.incrementAndGet() % 256);
        String ipB = "10.3.1." + (SEQ.incrementAndGet() % 256);

        for (int i = 0; i < 10; i++) {
            assertThat(rateLimiter.checkPurchase(ipA, SEQ.incrementAndGet()))
                    .as("IP 第 %d 次應放行", i + 1).isEmpty();
        }
        assertThat(rateLimiter.checkPurchase(ipA, SEQ.incrementAndGet())).as("IP 第 11 次應被單 IP 層擋下")
                .contains(RateLimitLayer.IP);

        // 不同 IP 各自獨立
        assertThat(rateLimiter.checkPurchase(ipB, SEQ.incrementAndGet())).isEmpty();
    }

    @Test
    void anonymousPurchaseChecksOnlyGlobalAndIp() {
        String ip = "10.4.0." + (SEQ.incrementAndGet() % 256);
        // 單用戶容量是 2;未登入時不查這層,所以第 3 次仍放行,直到單 IP 的 10 次
        for (int i = 0; i < 10; i++) {
            assertThat(rateLimiter.checkPurchase(ip, null)).as("第 %d 次應放行", i + 1).isEmpty();
        }
        assertThat(rateLimiter.checkPurchase(ip, null)).contains(RateLimitLayer.IP);
    }

    /**
     * ADR 0010 §3 的核心:一個人狂刷被單用戶層擋下的請求,不可消耗全域額度。
     * 舊的逐層 tryConsume 寫法下,下面 50 次請求會先吃光全域的 20 個 token,
     * 之後換任何人來都會被全域層擋下。
     */
    @Test
    void requestsRejectedByUserLayerDoNotConsumeGlobalTokens() {
        long spammer = SEQ.incrementAndGet();
        int allowed = 0;
        for (int i = 0; i < 50; i++) {
            // 每次換 IP,讓單 IP 層不會先擋下,擋下的一定是單用戶層
            Optional<RateLimitLayer> r = rateLimiter.checkPurchase("10.5.0." + i, spammer);
            if (r.isEmpty()) {
                allowed++;
            } else {
                assertThat(r).contains(RateLimitLayer.USER);
            }
        }
        // 單用戶 2/s,這段迴圈只會跑幾十毫秒,補充最多再多 1 次
        assertThat(allowed).isBetween(2, 3);

        // 全域只被放行的那幾次扣掉,其他人還能用掉剩下的額度
        for (int i = 0; i < GLOBAL - allowed; i++) {
            assertThat(freshPurchase()).as("其他人第 %d 次應放行", i + 1).isEmpty();
        }
    }

    @Test
    void requestsRejectedByIpLayerDoNotConsumeGlobalTokens() {
        String ip = "10.6.0." + (SEQ.incrementAndGet() % 256);
        int allowed = 0;
        for (int i = 0; i < 50; i++) {
            if (rateLimiter.checkPurchase(ip, SEQ.incrementAndGet()).isEmpty()) {
                allowed++;
            }
        }
        assertThat(allowed).isBetween(10, 11);
        for (int i = 0; i < GLOBAL - allowed; i++) {
            assertThat(freshPurchase()).as("其他 IP 第 %d 次應放行", i + 1).isEmpty();
        }
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

    /** ADR 0010 §5:部署當下 Bucket4j 留下的字串型舊 key 還在,新 key 不可受影響(同名會 WRONGTYPE)。 */
    @Test
    void legacyBucket4jKeysDoNotInterfere() {
        redisTemplate.opsForValue().set("seckill:rl:global", "legacy-bucket4j-state");
        try {
            assertThat(freshPurchase()).isEmpty();
        } finally {
            redisTemplate.delete("seckill:rl:global");
        }
    }

    /**
     * ADR 0010 §6:限流走專用長駐連線。{@code StringRedisTemplate} 在本專案每次呼叫都新開一條 TCP 連線
     * ({@code RedisConfig} 關閉 shareNativeConnection 且未開連線池),限流若走它,每個搶購請求就是一次連線建立。
     */
    @Test
    void checksReuseOneConnection() {
        LongSupplier connectionsReceived = () -> Long.parseLong(redisTemplate.execute(
                (RedisCallback<Properties>) c -> c.serverCommands().info("stats"))
                .getProperty("total_connections_received"));
        long before = connectionsReceived.getAsLong();
        for (int i = 0; i < 100; i++) {
            freshPurchase();
        }
        // 走 template 會是 ~100 條。容器跨測試類別共用,同一時間其他元件(listener、INFO 本身)也會開連線,
        // 實測有個位數的背景噪音,所以用倍數差距判斷而不是精確值
        assertThat(connectionsReceived.getAsLong() - before).isLessThan(20);
    }

    @Test
    void bucketStateIsHashWithTtl() {
        assertThat(freshPurchase()).isEmpty();
        assertThat(redisTemplate.opsForHash().keys(GLOBAL_KEY)).containsExactlyInAnyOrder("t", "ts");
        Long ttl = redisTemplate.getExpire(GLOBAL_KEY);
        assertThat(ttl).isNotNull().isBetween(1L, 2L);
    }

    /**
     * 多執行緒同時打全域層:放行數不超過「容量 + 經過時間的補充量」(不超放),
     * 也不少於容量(不因競爭而少放;Bucket4j CAS 在高競爭下會掉到設定值以下,報告 §14.5)。
     */
    @Test
    void concurrentRequestsNeitherOverNorUnderIssueGlobalTokens() throws Exception {
        int threads = 16;
        int attemptsPerThread = 25;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        long t0;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    int ok = 0;
                    for (int j = 0; j < attemptsPerThread; j++) {
                        if (freshPurchase().isEmpty()) {
                            ok++;
                        }
                    }
                    return ok;
                }));
            }
            t0 = System.nanoTime();
            start.countDown();
            int allowed = 0;
            for (Future<Integer> f : futures) {
                allowed += f.get();
            }
            double elapsedSeconds = (System.nanoTime() - t0) / 1_000_000_000.0;

            // 400 次請求遠大於容量,桶一定會被取空
            assertThat(allowed).as("不少放").isGreaterThanOrEqualTo(GLOBAL);
            assertThat(allowed).as("不超放(經過 %.3f 秒)", elapsedSeconds)
                    .isLessThanOrEqualTo(GLOBAL + (int) Math.ceil(GLOBAL * elapsedSeconds));
        }
    }
}
