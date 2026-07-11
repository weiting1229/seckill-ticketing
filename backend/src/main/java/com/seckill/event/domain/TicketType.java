package com.seckill.event.domain;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Data;

/**
 * 票種(搶購標的,對應 ticket_types 表)。主鍵為 Snowflake ID。
 *
 * <p>{@code stockRemaining} 為 DB 端防超賣底線;上線後另有 Redis 預扣庫存
 * ({@code seckill:stock:{id}})承接熱路徑,兩者由對帳 API 比對。
 */
@Data
public class TicketType {
    private Long id;
    private Long eventId;
    private String name;
    private BigDecimal price;
    private Integer totalStock;
    private Integer stockRemaining;
    private Instant seckillStart;
    private Instant seckillEnd;
    private TicketTypeStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
