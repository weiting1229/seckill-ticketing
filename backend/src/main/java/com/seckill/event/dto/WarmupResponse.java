package com.seckill.event.dto;

/**
 * 庫存預熱結果(admin)。
 *
 * <p>{@code alreadyWarmed} 反映冪等語意:首次預熱為 false;重複呼叫(票種已 ONLINE)為 true。
 * {@code redisStockRemaining} 為預熱後 Redis 現值——重複呼叫時保留現值(不覆蓋已扣減庫存)。
 */
public record WarmupResponse(
        String ticketTypeId,
        String status,
        Integer dbStockRemaining,
        Integer redisStockRemaining,
        boolean alreadyWarmed
) {
}
