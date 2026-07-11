package com.seckill.event.dto;

import com.seckill.event.domain.TicketType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 活動詳情中的票種視圖(公開 API)。
 *
 * <p>{@code remaining} 為即時剩餘庫存,讀自 Redis({@code seckill:stock:{id}}),
 * 允許短暫不精確;未預熱(OFFLINE)或 Redis 無值時為 {@code null}。
 */
public record TicketTypeView(
        String id,
        String name,
        BigDecimal price,
        Integer totalStock,
        Integer remaining,
        Instant seckillStart,
        Instant seckillEnd,
        String status
) {
    public static TicketTypeView of(TicketType t, Integer remaining) {
        return new TicketTypeView(
                String.valueOf(t.getId()), t.getName(), t.getPrice(), t.getTotalStock(),
                remaining, t.getSeckillStart(), t.getSeckillEnd(), t.getStatus().name());
    }
}
