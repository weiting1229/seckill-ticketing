package com.seckill.order.domain;

/** 庫存流水類型(設計文件第 5 節 stock_logs.type)。DEDUCT 扣減(-1)/ REVERT 回補(+1)。 */
public enum StockLogType {
    DEDUCT,
    REVERT
}
