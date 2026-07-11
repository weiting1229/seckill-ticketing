package com.seckill.common.id;

/**
 * 分散式 ID 產生器對外介面(設計文件第 8 節)。
 * 目前實作為 Snowflake 變體;未來多實例可替換為從 Redis 分配 workerId 的版本。
 */
public interface IdGenerator {

    /** 產生一個全域唯一、趨勢遞增的 64-bit ID。 */
    long nextId();
}
