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
 * 更新票種入參(admin,PUT 全量)。僅 OFFLINE 票種可更新(ONLINE 回 2005)。
 * totalStock 變更會同步重設 stockRemaining(尚未預熱,無已扣減庫存需保護)。
 */
public record UpdateTicketTypeRequest(

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
