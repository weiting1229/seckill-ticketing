package com.seckill.bench;

/**
 * 每個 worker 私有的固定解析度延遲直方圖(不共享、不加鎖,避免量測本身造成競爭)。
 *
 * <p>兩段解析度:0–10ms 用 10µs 一格(1000 格),10–1000ms 用 1ms 一格(990 格),超過 1s 進溢位格。
 * 要看的是毫秒級的 p99 拐點,這個解析度足夠;用共享的原子直方圖或 HdrHistogram 都會多帶一份
 * 競爭或相依進來,不划算。
 */
final class Histogram {

    private static final int FINE_BUCKETS = 1000;      // 0–10ms,每格 10µs
    private static final long FINE_STEP_NANOS = 10_000L;
    private static final long FINE_MAX_NANOS = FINE_BUCKETS * FINE_STEP_NANOS; // 10ms
    private static final int COARSE_BUCKETS = 990;     // 10ms–1000ms,每格 1ms
    private static final long COARSE_STEP_NANOS = 1_000_000L;

    private final long[] counts = new long[FINE_BUCKETS + COARSE_BUCKETS + 1];
    private long total;
    private long maxNanos;

    void record(long nanos) {
        total++;
        if (nanos > maxNanos) maxNanos = nanos;
        counts[indexOf(nanos)]++;
    }

    private static int indexOf(long nanos) {
        if (nanos < FINE_MAX_NANOS) {
            return (int) (nanos / FINE_STEP_NANOS);
        }
        int coarse = (int) ((nanos - FINE_MAX_NANOS) / COARSE_STEP_NANOS);
        if (coarse >= COARSE_BUCKETS) {
            return FINE_BUCKETS + COARSE_BUCKETS; // 溢位格(>1s)
        }
        return FINE_BUCKETS + coarse;
    }

    /** 這一格的上界(納秒);用上界回報,所以報出來的數字是「不超過」的保證值。 */
    private static long upperBoundNanos(int index) {
        if (index < FINE_BUCKETS) {
            return (index + 1) * FINE_STEP_NANOS;
        }
        if (index < FINE_BUCKETS + COARSE_BUCKETS) {
            return FINE_MAX_NANOS + (long) (index - FINE_BUCKETS + 1) * COARSE_STEP_NANOS;
        }
        return Long.MAX_VALUE;
    }

    void mergeFrom(Histogram other) {
        for (int i = 0; i < counts.length; i++) {
            counts[i] += other.counts[i];
        }
        total += other.total;
        if (other.maxNanos > maxNanos) maxNanos = other.maxNanos;
    }

    long count() {
        return total;
    }

    long maxMicros() {
        return maxNanos / 1000;
    }

    /** 回傳該分位數的微秒值;直方圖為上界估計,溢位格回報 -1 表示「>1s,無法估」。 */
    long percentileMicros(double percentile) {
        if (total == 0) return 0;
        long target = (long) Math.ceil(percentile / 100.0 * total);
        long seen = 0;
        for (int i = 0; i < counts.length; i++) {
            seen += counts[i];
            if (seen >= target) {
                long bound = upperBoundNanos(i);
                return bound == Long.MAX_VALUE ? -1 : bound / 1000;
            }
        }
        return -1;
    }
}
