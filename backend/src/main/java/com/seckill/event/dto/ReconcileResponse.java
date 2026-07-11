package com.seckill.event.dto;

/**
 * 對帳結果(admin,設計文件第 9 節)。比對四方:
 * <ul>
 *   <li>{@code dbStockRemaining}:ticket_types.stock_remaining(DB 底線)</li>
 *   <li>{@code redisStockRemaining}:Redis 預扣庫存現值(未預熱/過期時為 null)</li>
 *   <li>{@code validOrderCount}:有效訂單數(PAID + PENDING_PAYMENT)</li>
 *   <li>{@code stockLogNetDelta}:stock_logs 淨值(SUM(delta),扣減 -1 / 回補 +1)</li>
 * </ul>
 *
 * <p>{@code soldByDb = totalStock - dbStockRemaining}。三方一致的判定:
 * DB 剩餘 == Redis 剩餘、售出量 == 有效訂單數、且 DB 剩餘 == totalStock + 日誌淨值。
 */
public record ReconcileResponse(
        String ticketTypeId,
        Integer totalStock,
        Integer dbStockRemaining,
        Integer redisStockRemaining,
        long validOrderCount,
        int stockLogNetDelta,
        int soldByDb,
        boolean consistent
) {
}
