package com.seckill.event.dto;

import com.seckill.event.domain.Event;
import java.time.Instant;

/**
 * 活動完整視圖(admin)。含 DRAFT 等未發布狀態,故僅 admin 端點回傳。
 */
public record EventAdminResponse(
        String id,
        String title,
        String description,
        String venue,
        String coverImageUrl,
        Instant eventTime,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public static EventAdminResponse from(Event e) {
        return new EventAdminResponse(
                String.valueOf(e.getId()), e.getTitle(), e.getDescription(), e.getVenue(),
                e.getCoverImageUrl(), e.getEventTime(), e.getStatus().name(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
