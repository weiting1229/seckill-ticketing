package com.seckill.event.domain;

/**
 * 票種狀態(設計文件第 5 節 ticket_types.status)。MyBatis 以 name 存為 VARCHAR。
 *
 * <p>OFFLINE:尚未上線,可自由編輯/刪除;ONLINE:已由 warmup 上線並寫入 Redis 庫存,
 * 為避免破壞已預熱庫存,一旦 ONLINE 即不可修改或刪除。
 */
public enum TicketTypeStatus {
    OFFLINE,
    ONLINE
}
