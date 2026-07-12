package com.seckill.order.domain;

/**
 * 訂單狀態(設計文件第 5 節 orders.status)。MyBatis 以 name 存為 VARCHAR。
 * 狀態轉移(支付/取消/超時)一律條件 UPDATE 落實於 SQL 層,屬 M4;M3 僅建立 PENDING_PAYMENT。
 */
public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    CANCELLED,
    EXPIRED
}
