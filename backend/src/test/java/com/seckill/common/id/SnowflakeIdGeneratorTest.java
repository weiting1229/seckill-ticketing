package com.seckill.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Snowflake ID 產生器測試(設計文件第 8 節驗收要求)。 */
class SnowflakeIdGeneratorTest {

    private SnowflakeIdGenerator newGenerator() {
        return new SnowflakeIdGenerator(0, 0, new SimpleMeterRegistry());
    }

    @Test
    void singleThreadShouldProduceUniqueAndStrictlyIncreasingIds() {
        SnowflakeIdGenerator generator = newGenerator();
        int count = 200_000;
        Set<Long> ids = new HashSet<>(count * 2);
        long previous = Long.MIN_VALUE;

        for (int i = 0; i < count; i++) {
            long id = generator.nextId();
            assertThat(id).isPositive();
            assertThat(id).isGreaterThan(previous);   // 趨勢遞增(單執行緒嚴格遞增)
            assertThat(ids.add(id)).isTrue();          // 唯一性
            previous = id;
        }
    }

    @Test
    void concurrent32ThreadsOneMillionIdsShouldHaveZeroCollision() throws Exception {
        SnowflakeIdGenerator generator = newGenerator();
        int threads = 32;
        int perThread = 31_250;                        // 32 * 31250 = 1,000,000
        int total = threads * perThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<List<Long>>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                tasks.add(() -> {
                    List<Long> local = new ArrayList<>(perThread);
                    for (int i = 0; i < perThread; i++) {
                        local.add(generator.nextId());
                    }
                    return local;
                });
            }

            Set<Long> all = new HashSet<>(total * 2);
            for (Future<List<Long>> future : pool.invokeAll(tasks)) {
                all.addAll(future.get());
            }

            assertThat(all).hasSize(total);            // 零碰撞
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void clockMovedBackwardsBeyondToleranceShouldThrowAndCountMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ControllableClockGenerator generator = new ControllableClockGenerator(registry);

        generator.now = SnowflakeIdGenerator.EPOCH_MILLI + 1_000;
        generator.nextId();                            // 正常發號,記錄 lastTimestamp

        generator.now -= (SnowflakeIdGenerator.MAX_BACKWARD_MILLIS + 5);  // 回撥 10ms > 5ms

        assertThatThrownBy(generator::nextId)
                .isInstanceOf(ClockMovedBackwardsException.class);
        assertThat(registry.get("id_generator_clock_backwards_total").counter().count())
                .isEqualTo(1.0);
    }

    /** 可控時鐘的測試子類(同 package 才能覆寫 protected 方法)。 */
    private static final class ControllableClockGenerator extends SnowflakeIdGenerator {
        private long now;

        ControllableClockGenerator(SimpleMeterRegistry registry) {
            super(0, 0, registry);
        }

        @Override
        protected long currentTimeMillis() {
            return now;
        }
    }
}
