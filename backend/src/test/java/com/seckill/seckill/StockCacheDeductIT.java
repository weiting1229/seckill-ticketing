package com.seckill.seckill;

import static org.assertj.core.api.Assertions.assertThat;

import com.seckill.event.service.StockCache;
import com.seckill.support.AbstractIntegrationTest;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 搶購原子扣減 / 回補核心(子項 A)整合測試:直接呼叫 {@link StockCache},不經 API。
 *
 * <p>驗證 seckill_deduct.lua 的四種回傳、seckill_revert.lua 的回補,
 * 以及高併發下的<b>零超賣</b>(50 執行緒搶 10 張 → 恰 10 次成功、庫存歸零)。
 */
class StockCacheDeductIT extends AbstractIntegrationTest {

    private static final String STOCK_KEY = "seckill:stock:";
    private static final String BOUGHT_KEY = "seckill:bought:";
    /** 各測試用不同 ticketTypeId,避免共用 singleton 容器時 key 互相干擾。 */
    private static final AtomicLong TT_SEQ = new AtomicLong(700_000L);

    @Autowired
    StockCache stockCache;

    @Autowired
    StringRedisTemplate redisTemplate;

    private long newTicketType(int stock) {
        long ttId = TT_SEQ.incrementAndGet();
        redisTemplate.opsForValue().set(STOCK_KEY + ttId, String.valueOf(stock));
        return ttId;
    }

    @Test
    void deductSuccessDecrementsStockAndRecordsBought() {
        long ttId = newTicketType(5);
        long userId = 111L;

        assertThat(stockCache.deduct(ttId, userId)).isEqualTo(StockCache.DEDUCT_SUCCESS);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY + ttId)).isEqualTo("4");
        assertThat(redisTemplate.opsForSet().isMember(BOUGHT_KEY + ttId, "111")).isTrue();
    }

    @Test
    void deductDuplicateReturnsMinusOne() {
        long ttId = newTicketType(5);
        long userId = 222L;

        assertThat(stockCache.deduct(ttId, userId)).isEqualTo(StockCache.DEDUCT_SUCCESS);
        // 同一用戶再搶:重複購買,庫存不再變動(仍為 4)
        assertThat(stockCache.deduct(ttId, userId)).isEqualTo(StockCache.DEDUCT_DUPLICATE);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY + ttId)).isEqualTo("4");
    }

    @Test
    void deductSoldOutReturnsMinusTwo() {
        long ttId = newTicketType(1);
        assertThat(stockCache.deduct(ttId, 1L)).isEqualTo(StockCache.DEDUCT_SUCCESS);
        // 庫存已 0,不同用戶搶:售罄
        assertThat(stockCache.deduct(ttId, 2L)).isEqualTo(StockCache.DEDUCT_SOLD_OUT);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY + ttId)).isEqualTo("0");
    }

    @Test
    void deductNotWarmedReturnsMinusThree() {
        long ttId = TT_SEQ.incrementAndGet(); // 刻意不預熱
        assertThat(stockCache.deduct(ttId, 1L)).isEqualTo(StockCache.DEDUCT_NOT_WARMED);
    }

    @Test
    void revertRestoresStockAndRemovesBought() {
        long ttId = newTicketType(5);
        long userId = 333L;
        stockCache.deduct(ttId, userId);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY + ttId)).isEqualTo("4");

        stockCache.revert(ttId, userId);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY + ttId)).isEqualTo("5");
        assertThat(redisTemplate.opsForSet().isMember(BOUGHT_KEY + ttId, "333")).isFalse();
        // 回補後該用戶可再次成功扣減
        assertThat(stockCache.deduct(ttId, userId)).isEqualTo(StockCache.DEDUCT_SUCCESS);
    }

    @Test
    void boughtSetInheritsStockTtlOnFirstDeduct() {
        long ttId = TT_SEQ.incrementAndGet();
        // stock key 帶 3600s TTL(模擬 warmup);bought 集合首建應對齊此 TTL
        redisTemplate.opsForValue().set(STOCK_KEY + ttId, "5", java.time.Duration.ofSeconds(3600));

        stockCache.deduct(ttId, 444L);
        Long boughtTtl = redisTemplate.getExpire(BOUGHT_KEY + ttId);
        assertThat(boughtTtl).isNotNull();
        assertThat(boughtTtl).isBetween(1L, 3600L);

        // 第二位買家不重設 TTL(非滑動):TTL 不應超過 stock 的 3600s 上限
        stockCache.deduct(ttId, 555L);
        Long boughtTtl2 = redisTemplate.getExpire(BOUGHT_KEY + ttId);
        assertThat(boughtTtl2).isNotNull();
        assertThat(boughtTtl2).isLessThanOrEqualTo(boughtTtl);
    }

    @Test
    void concurrentDeductNeverOversells() throws Exception {
        int stock = 10;
        int threads = 50;
        long ttId = newTicketType(stock);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Long>> tasks = java.util.stream.IntStream.range(0, threads)
                    .<Callable<Long>>mapToObj(i -> () -> stockCache.deduct(ttId, 1000L + i))
                    .toList();
            List<Future<Long>> futures = pool.invokeAll(tasks);

            long successes = 0;
            for (Future<Long> f : futures) {
                if (f.get() == StockCache.DEDUCT_SUCCESS) {
                    successes++;
                }
            }
            // 恰好 stock 次成功,零超賣
            assertThat(successes).isEqualTo(stock);
        } finally {
            pool.shutdown();
        }

        assertThat(redisTemplate.opsForValue().get(STOCK_KEY + ttId)).isEqualTo("0");
        assertThat(redisTemplate.opsForSet().size(BOUGHT_KEY + ttId)).isEqualTo((long) stock);
    }
}
