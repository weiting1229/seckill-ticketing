package com.seckill.order.mq;

/**
 * 延遲取消訊息(設計文件第 7 節)。建單成功後發至 {@code order.delay.exchange};在 order.delay.queue
 * 滯留至 TTL 到期後 dead-letter 至 order.timeout.queue,由 {@code OrderTimeoutListener} 觸發超時取消。
 *
 * <p>取消以 {@code orderId} 為權威鍵(消費者據此對訂單做條件 UPDATE);{@code ticketTypeId} / {@code userId}
 * 供結構化日誌與除錯觀測(回補所需值由 {@code OrderCancelService} 依訂單重新載入,單一事實來源)。
 * 與 {@code OrderMessage} 同套件,Jackson 以 __TypeId__ 還原型別時落在 RabbitConfig 的 trustedPackages。
 *
 * @param orderId      待超時取消的訂單(orders.id)
 * @param ticketTypeId 票種(觀測用)
 * @param userId       買家(觀測用)
 */
public record OrderDelayMessage(
        long orderId,
        long ticketTypeId,
        long userId) {
}
