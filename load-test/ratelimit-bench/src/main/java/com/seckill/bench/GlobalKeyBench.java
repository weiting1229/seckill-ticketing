package com.seckill.bench;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;

/**
 * 階段 3:量單一 Redis 節點面對瞬間爆量的 Bucket4j CAS 檢查,能撐到多少併發而不讓排隊延遲失控。
 *
 * <p><b>為什麼要有這支程式</b>:階段 1 想用 HTTP 流量把 {@code seckill:rl:global} 壓到門檻量級,
 * 但 1-2 證明單機 k6 的 one-shot 情境只產得出約 65 次/秒(門檻的 2%),而且量到的會混進 TLS、
 * Caddy、Tomcat 的影響。這支程式直接在主機的 Docker 網路內跑,只做一件事:
 * 用與正式程式碼<b>完全相同</b>的 Bucket4j + Lettuce CAS 路徑打同一把 key。
 *
 * <p><b>與正式程式碼的對應</b>(偏離任何一項,量到的就不是正式站在跑的東西):
 * <ul>
 *   <li>{@code Bucket4jLettuce.casBasedBuilder} + {@code basedOnTimeForRefillingBucketUpToMax(10s)}
 *       —— 同 {@code com.seckill.config.RateLimitConfig}</li>
 *   <li>codec {@code RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)} —— 同上</li>
 *   <li>單一共享 {@code StatefulRedisConnection} —— 同上(連線本身的多工也是被測對象的一部分)</li>
 *   <li>每次都重新 {@code getProxy(key, cfg)} 再 {@code tryConsume(1)}
 *       —— 同 {@code RateLimiterService.tryGlobal()},不快取 proxy</li>
 *   <li>桶設定 {@code capacity(N).refillGreedy(N, 1s)} —— 同 {@code RateLimiterService.perSecond()}</li>
 * </ul>
 *
 * <p><b>量法</b>:閉環(closed loop)—— 每個 worker 不斷送出、拿到回應就立刻送下一個,所以
 * 「併發數」是在途請求數,吞吐是量出來的結果而不是設定值。逐階加大併發找延遲拐點。
 * 計時涵蓋從呼叫到拿到回應的 wall-clock,與 {@code seckill.ratelimit.check.duration} 同定義,
 * 天然含 Redis 單執行緒的佇列等待。
 *
 * <p><b>{@code BENCH_MODE=lua}</b>(計畫 §6,Lua token bucket 評估):改打 {@code token_bucket.lua},
 * 讀、算、寫在 Redis 內一次完成,沒有 CAS 重試。同樣是單一共享連線、同樣的閉環量法,
 * 兩種模式的數字才能直接對照。
 *
 * <p>用法(環境變數):
 * <pre>
 *   BENCH_MODE           bucket4j(預設,正式站現行路徑)或 lua
 *   REDIS_URI            必填,例如 redis://:password@seckill-redis-bench:6379/0
 *   BENCH_KEY            預設 seckill:rl:global
 *   BENCH_CAPACITY       預設 3000(對齊正式站 global-capacity)
 *   BENCH_STEPS          預設 50,100,200,400,800,1600(逗號分隔的併發數)
 *   BENCH_STEP_SECONDS   每階持續秒數,預設 30
 *   BENCH_WARMUP_SECONDS 每階正式計時前的暖身秒數,預設 5
 *   BENCH_GAP_SECONDS    階與階之間的靜置秒數,預設 10
 * </pre>
 */
public final class GlobalKeyBench {

    public static void main(String[] args) throws Exception {
        String redisUri = require("REDIS_URI");
        String key = env("BENCH_KEY", "seckill:rl:global");
        long capacity = Long.parseLong(env("BENCH_CAPACITY", "3000"));
        String rawSteps = env("BENCH_STEPS", "50,100,200,400,800,1600");
        int[] steps = parseSteps(rawSteps);
        int stepSeconds = Integer.parseInt(env("BENCH_STEP_SECONDS", "30"));
        int warmupSeconds = Integer.parseInt(env("BENCH_WARMUP_SECONDS", "5"));
        int gapSeconds = Integer.parseInt(env("BENCH_GAP_SECONDS", "10"));
        String mode = env("BENCH_MODE", "bucket4j");
        if (!mode.equals("bucket4j") && !mode.equals("lua")) {
            throw new IllegalStateException("BENCH_MODE 只能是 bucket4j 或 lua,收到 " + mode);
        }

        System.out.printf("mode=%s key=%s capacity=%d steps=%s stepSeconds=%d warmupSeconds=%d%n",
                mode, key, capacity, rawSteps, stepSeconds, warmupSeconds);
        // 不印 REDIS_URI:裡面有密碼(CLAUDE.md:祕密不進日誌)
        System.out.printf("redis host=%s%n", RedisURI.create(redisUri).getHost());

        RedisClient client = RedisClient.create(RedisURI.create(redisUri));
        try (StatefulRedisConnection<String, byte[]> conn =
                     client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE))) {

            BooleanSupplier check = mode.equals("lua")
                    ? luaCheck(conn, key, capacity)
                    : bucket4jCheck(conn, key, capacity);

            System.out.println();
            System.out.println("concurrency |     ops |  ops/s | allowed | throttled | errors |    p50 |    p90 |    p99 |  p99.9 |    max");
            System.out.println("------------+---------+--------+---------+-----------+--------+--------+--------+--------+--------+-------");

            for (int concurrency : steps) {
                StepResult r = runStep(check, concurrency, warmupSeconds, stepSeconds);
                System.out.printf("%11d | %7d | %6.0f | %7d | %9d | %6d | %6s | %6s | %6s | %6s | %6d%n",
                        concurrency, r.ops(), r.opsPerSecond(), r.allowed(), r.throttled(), r.errors(),
                        fmt(r.p50Micros()), fmt(r.p90Micros()), fmt(r.p99Micros()), fmt(r.p999Micros()),
                        r.maxMicros());
                if (gapSeconds > 0 && concurrency != steps[steps.length - 1]) {
                    Thread.sleep(gapSeconds * 1000L);
                }
            }
            System.out.println();
            // 這行刻意用純 ASCII:Windows 主控台預設 cp950,中文會變亂碼(實測過)
            System.out.println("latency unit = microseconds; '>1s' = overflow bucket, not estimable");
        } finally {
            client.shutdown();
        }
    }

    private record StepResult(long ops, double opsPerSecond, long allowed, long throttled, long errors,
                              long p50Micros, long p90Micros, long p99Micros, long p999Micros, long maxMicros) {
    }

    /** 正式站現行路徑,逐項對應見類別註解。 */
    private static BooleanSupplier bucket4jCheck(StatefulRedisConnection<String, byte[]> conn,
                                                 String key, long capacity) {
        ProxyManager<String> proxyManager = Bucket4jLettuce.casBasedBuilder(conn)
                .expirationAfterWrite(ExpirationAfterWriteStrategy
                        .basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(10)))
                .build();
        BucketConfiguration config = BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, Duration.ofSeconds(1)))
                .build();
        return () -> proxyManager.getProxy(key, () -> config).tryConsume(1);
    }

    /**
     * 候選路徑:一次 EVALSHA。腳本開跑前 SCRIPT LOAD 一次,之後只送 SHA,
     * 與正式站 Spring {@code DefaultRedisScript} 走 EVALSHA 的行為一致。
     */
    private static BooleanSupplier luaCheck(StatefulRedisConnection<String, byte[]> conn,
                                            String key, long capacity) throws IOException {
        byte[] script;
        try (InputStream in = GlobalKeyBench.class.getResourceAsStream("/token_bucket.lua")) {
            if (in == null) {
                throw new IllegalStateException("jar 內找不到 token_bucket.lua");
            }
            script = in.readAllBytes();
        }
        String sha = conn.sync().scriptLoad(script);
        String[] keys = {key};
        byte[] capacityArg = Long.toString(capacity).getBytes(StandardCharsets.US_ASCII);
        return () -> {
            Long r = conn.sync().evalsha(sha, ScriptOutputType.INTEGER, keys, capacityArg, capacityArg);
            return r != null && r == 1L;
        };
    }

    private static StepResult runStep(BooleanSupplier check, int concurrency, int warmupSeconds, int stepSeconds)
            throws InterruptedException {
        AtomicBoolean recording = new AtomicBoolean(false);
        AtomicBoolean running = new AtomicBoolean(true);
        LongAdder allowed = new LongAdder();
        LongAdder throttled = new LongAdder();
        LongAdder errors = new LongAdder();
        List<Histogram> histograms = new ArrayList<>(concurrency);
        CountDownLatch done = new CountDownLatch(concurrency);

        for (int i = 0; i < concurrency; i++) {
            Histogram h = new Histogram();
            histograms.add(h);
            // 虛擬執行緒:backend 也開了 virtual threads,且 1600 條平台執行緒在 4 核上光是排程就會失真
            Thread.ofVirtual().start(() -> {
                try {
                    while (running.get()) {
                        long start = System.nanoTime();
                        boolean ok;
                        try {
                            ok = check.getAsBoolean();
                        } catch (RuntimeException e) {
                            errors.increment();
                            continue;
                        }
                        long elapsed = System.nanoTime() - start;
                        if (recording.get()) {
                            h.record(elapsed);
                            if (ok) {
                                allowed.increment();
                            } else {
                                throttled.increment();
                            }
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        Thread.sleep(warmupSeconds * 1000L);
        recording.set(true);
        long t0 = System.nanoTime();
        Thread.sleep(stepSeconds * 1000L);
        recording.set(false);
        double elapsedSeconds = (System.nanoTime() - t0) / 1_000_000_000.0;
        running.set(false);
        done.await();

        Histogram merged = new Histogram();
        for (Histogram h : histograms) {
            merged.mergeFrom(h);
        }
        long ops = merged.count();
        return new StepResult(ops, ops / elapsedSeconds, allowed.sum(), throttled.sum(), errors.sum(),
                merged.percentileMicros(50), merged.percentileMicros(90),
                merged.percentileMicros(99), merged.percentileMicros(99.9), merged.maxMicros());
    }

    private static String fmt(long micros) {
        return micros < 0 ? ">1s" : Long.toString(micros);
    }

    private static int[] parseSteps(String raw) {
        String[] parts = raw.split(",");
        int[] steps = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            steps[i] = Integer.parseInt(parts[i].trim());
        }
        return steps;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String require(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("缺少必要環境變數 " + name);
        }
        return v;
    }

    private GlobalKeyBench() {
    }
}
