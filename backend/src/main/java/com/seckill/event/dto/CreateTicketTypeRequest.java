package com.seckill.event.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 建立票種入參(admin)。新建票種狀態一律 OFFLINE,且 stockRemaining 初始化為 totalStock。
 * seckillStart 須早於 seckillEnd,於 service 層跨欄位校驗(回 2006)。
 */
public record CreateTicketTypeRequest(

        @NotNull(message = "不可為空")
        Long eventId,

        @NotBlank(message = "不可為空")
        @Size(max = 100, message = "長度不可超過 100 字")
        String name,

        @NotNull(message = "不可為空")
        @DecimalMin(value = "0.0", message = "不可為負")
        @Digits(integer = 8, fraction = 2, message = "金額格式非法(最多 8 位整數 2 位小數)")
        BigDecimal price,

        @NotNull(message = "不可為空")
        @PositiveOrZero(message = "不可為負")
        Integer totalStock,

        @NotNull(message = "不可為空")
        Instant seckillStart,

        @NotNull(message = "不可為空")
        Instant seckillEnd
) {
}
