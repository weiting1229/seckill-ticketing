package com.seckill.common.id;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;

/**
 * Snowflake 變體 ID 產生器(設計文件第 8 節)。
 *
 * <p>位元結構(共 64 bit):
 * <pre>
 *   1 bit 符號(恆 0) | 41 bit 時間戳(相對自訂 epoch,毫秒) | 5 bit datacenterId | 5 bit workerId | 12 bit 序列號
 * </pre>
 *
 * <p>時鐘回撥策略:回撥 ≤ 5ms 自旋等待追平;> 5ms 拋 {@link ClockMovedBackwardsException} 拒絕發號,
 * 並累加 {@code id_generator_clock_backwards_total} counter 觸發告警。
 *
 * <p>執行緒安全:{@link #nextId()} 為 {@code synchronized}。
 */
public class SnowflakeIdGenerator implements IdGenerator {

    /** 自訂 epoch:2026-01-01T00:00:00Z(設計文件指定)。 */
    static final long EPOCH_MILLI = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);          // 31
    static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);  // 31
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);   // 4095

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;                              // 12
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;         // 17
    private static final long TIMESTAMP_SHIFT =
            SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;                            // 22

    /** 可自旋等待追平的最大時鐘回撥毫秒數;超過即拒絕發號。 */
    static final long MAX_BACKWARD_MILLIS = 5L;

    private final long workerId;
    private final long datacenterId;
    private final Counter clockBackwardsCounter;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(long workerId, long datacenterId, MeterRegistry meterRegistry) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "workerId 必須介於 0 ~ " + MAX_WORKER_ID + ",實際為 " + workerId);
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException(
                    "datacenterId 必須介於 0 ~ " + MAX_DATACENTER_ID + ",實際為 " + datacenterId);
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
        this.clockBackwardsCounter = Counter.builder("id_generator_clock_backwards_total")
                .description("時鐘回撥事件次數(> 5ms 拒絕發號)")
                .register(meterRegistry);
    }

    @Override
    public synchronized long nextId() {
        long timestamp = currentTimeMillis();

        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= MAX_BACKWARD_MILLIS) {
                // 小幅回撥:自旋等待時鐘追平
                while (timestamp < lastTimestamp) {
                    timestamp = currentTimeMillis();
                }
            } else {
                clockBackwardsCounter.increment();
                throw new ClockMovedBackwardsException(
                        "時鐘回撥 " + offset + "ms 超過容忍上限 " + MAX_BACKWARD_MILLIS + "ms,拒絕發號");
            }
        }

        if (timestamp == lastTimestamp) {
            // 同一毫秒內遞增序列號;溢位則等到下一毫秒
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0L) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH_MILLI) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long tilNextMillis(long lastMillis) {
        long timestamp = currentTimeMillis();
        while (timestamp <= lastMillis) {
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }

    /** 抽出成方法以利測試覆寫(模擬時鐘回撥)。 */
    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
