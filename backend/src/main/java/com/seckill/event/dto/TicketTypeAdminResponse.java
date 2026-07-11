package com.seckill.event.dto;

import com.seckill.event.domain.TicketType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 票種完整視圖(admin)。含 DB 端 stockRemaining(對帳底線),故僅 admin 端點回傳。
 */
public record TicketTypeAdminResponse(
        String id,
        String eventId,
        String name,
        BigDecimal price,
        Integer totalStock,
        Integer stockRemaining,
        Instant seckillStart,
        Instant seckillEnd,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public static TicketTypeAdminResponse from(TicketType t) {
        return new TicketTypeAdminResponse(
                String.valueOf(t.getId()), String.valueOf(t.getEventId()), t.getName(), t.getPrice(),
                t.getTotalStock(), t.getStockRemaining(), t.getSeckillStart(), t.getSeckillEnd(),
                t.getStatus().name(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
