package com.seckill.order.dto;

import com.seckill.order.domain.Order;
import com.seckill.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 訂單回應 DTO(設計文件第 9 節)。Snowflake ID 一律轉 String(避免前端 JS number 精度丟失);
 * 時間為 Instant(UTC),前端負責時區顯示。供支付與我的訂單列表 / 詳情共用。
 *
 * @param id           訂單號(orders.id)
 * @param eventId      活動 id
 * @param ticketTypeId 票種 id
 * @param price        單價
 * @param status       訂單狀態
 * @param createdAt    建立時間
 * @param paidAt       支付時間(未支付為 null)
 * @param expireAt     支付截止時間
 */
public record OrderResponse(
        String id,
        String eventId,
        String ticketTypeId,
        BigDecimal price,
        OrderStatus status,
        Instant createdAt,
        Instant paidAt,
        Instant expireAt) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                String.valueOf(order.getId()),
                String.valueOf(order.getEventId()),
                String.valueOf(order.getTicketTypeId()),
                order.getPrice(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getPaidAt(),
                order.getExpireAt());
    }
}
