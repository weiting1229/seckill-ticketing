package com.seckill.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * 建立活動入參(admin)。新建活動狀態一律為 DRAFT(不由入參指定)。
 */
public record CreateEventRequest(

        @NotBlank(message = "不可為空")
        @Size(max = 200, message = "長度不可超過 200 字")
        String title,

        @Size(max = 5000, message = "長度不可超過 5000 字")
        String description,

        @Size(max = 200, message = "長度不可超過 200 字")
        String venue,

        @NotNull(message = "不可為空")
        Instant eventTime
) {
}
