package com.seckill.order.domain;

import java.time.Instant;
import lombok.Data;

/** 庫存流水(對應 stock_logs 表,審計 + 對帳)。建單時寫一筆 DEDUCT(-1);回補場景寫 REVERT(+1,M4)。 */
@Data
public class StockLog {
    private Long id;
    private Long ticketTypeId;
    private Long orderId;
    private Integer delta;
    private StockLogType type;
    private Instant createdAt;
}
