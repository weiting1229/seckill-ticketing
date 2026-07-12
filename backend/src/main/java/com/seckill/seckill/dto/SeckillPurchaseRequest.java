package com.seckill.seckill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 搶購下單入參(設計文件第 9 節:POST /seckill/purchase,body 帶 ticketTypeId + token)。 */
public record SeckillPurchaseRequest(

        @NotNull(message = "不可為空")
        Long ticketTypeId,

        @NotBlank(message = "不可為空")
        String token
) {
}
