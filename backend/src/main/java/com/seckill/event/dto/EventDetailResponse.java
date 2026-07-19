package com.seckill.event.dto;

import com.seckill.event.domain.Event;
import java.time.Instant;
import java.util.List;

/**
 * 活動詳情(公開 API)。含票種清單與即時剩餘庫存,並回傳 {@code serverTime}
 * 供前端校準搶購倒數(避免依賴使用者本機時鐘)。
 */
public record EventDetailResponse(
        String id,
        String title,
        String description,
        String venue,
        String coverImageUrl,
        Instant eventTime,
        String status,
        List<TicketTypeView> ticketTypes,
        Instant serverTime
) {
    public static EventDetailResponse of(Event e, List<TicketTypeView> ticketTypes, Instant serverTime) {
        return new EventDetailResponse(
                String.valueOf(e.getId()), e.getTitle(), e.getDescription(), e.getVenue(),
                e.getCoverImageUrl(), e.getEventTime(), e.getStatus().name(), ticketTypes, serverTime);
    }
}
