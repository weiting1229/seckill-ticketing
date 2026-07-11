package com.seckill.event.dto;

import com.seckill.event.domain.EventStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * 更新活動入參(admin,PUT 全量)。含目標 {@code status};狀態轉移合法性於 service 層校驗
 * (見 {@link EventStatus#canTransitionTo}),非法轉移回 2002。
 */
public record UpdateEventRequest(

        @NotBlank(message = "不可為空")
        @Size(max = 200, message = "長度不可超過 200 字")
        String title,

        @Size(max = 5000, message = "長度不可超過 5000 字")
        String description,

        @Size(max = 200, message = "長度不可超過 200 字")
        String venue,

        @NotNull(message = "不可為空")
        Instant eventTime,

        @NotNull(message = "不可為空")
        EventStatus status
) {
}
