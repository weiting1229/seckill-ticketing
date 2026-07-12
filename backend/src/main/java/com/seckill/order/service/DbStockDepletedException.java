package com.seckill.order.service;

/**
 * DB 條件扣減庫存影響行數為 0(理論上不應發生,因 Redis 已於熱路徑攔截)。
 * 屬異常訊號:消費者捕獲後回補 Redis 庫存並將結果標記 FAIL,不重試(重試也不會成功)。
 * 為 RuntimeException,拋出即觸發 {@code @Transactional} 回滾(訂單不落庫)。
 */
public class DbStockDepletedException extends RuntimeException {

    public DbStockDepletedException(long ticketTypeId) {
        super("DB 庫存扣減失敗(售罄) ticketTypeId=" + ticketTypeId);
    }
}
