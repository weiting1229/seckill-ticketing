package com.seckill.order.mq;

/**
 * 建單訊息(設計文件第 7 節格式)。搶購熱路徑扣減成功後發至 {@code seckill.exchange};
 * 由建單消費者非同步落庫。生產者與消費者共用此類別(Jackson JSON 依 __TypeId__ 還原型別)。
 *
 * @param requestId    冪等鍵(UUID);對應 orders.request_id 唯一約束
 * @param userId       買家
 * @param ticketTypeId 票種
 * @param orderId      預先產生的 Snowflake 訂單號(直接作為 orders.id)
 * @param timestamp    發訊當下 epoch 毫秒;供 seckill_order_create_duration 直方圖計算落庫耗時
 */
public record OrderMessage(
        String requestId,
        long userId,
        long ticketTypeId,
        long orderId,
        long timestamp) {
}
