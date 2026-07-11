package com.seckill.event.dto;

import com.seckill.event.domain.Event;
import java.time.Instant;

/**
 * 活動列表項(公開 API)。id 以 String 回傳,避免前端 JS number 精度損失。
 */
public record EventSummaryResponse(
        String id,
        String title,
        String venue,
        Instant eventTime,
        String status
) {
    public static EventSummaryResponse from(Event e) {
        return new EventSummaryResponse(
                String.valueOf(e.getId()), e.getTitle(), e.getVenue(),
                e.getEventTime(), e.getStatus().name());
    }
}
