package com.seckill.event.domain;

import java.time.Instant;
import lombok.Data;

/**
 * 演唱會活動(對應 events 表)。主鍵為 Snowflake ID。
 * 時間一律 {@link Instant}(UTC),對應 TIMESTAMPTZ,前端負責時區顯示。
 */
@Data
public class Event {
    private Long id;
    private String title;
    private String description;
    private String venue;
    private Instant eventTime;
    private EventStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
