package com.seckill.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Data;

/**
 * 訂單(對應 orders 表)。主鍵為搶購熱路徑預先產生的 Snowflake ID(直接作為訂單號)。
 *
 * <p>{@code requestId} 為冪等鍵(來自 MQ 訊息),對應 {@code uq_orders_request};
 * {@code (userId, ticketTypeId)} 對應 {@code uq_orders_user_ticket}(每人每票種限購一張)。
 */
@Data
public class Order {
    private Long id;
    private Long userId;
    private Long eventId;
    private Long ticketTypeId;
    private BigDecimal price;
    private OrderStatus status;
    private String requestId;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant paidAt;
    private Instant expireAt;
}
