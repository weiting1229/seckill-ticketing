package com.seckill.seckill.dto;

import jakarta.validation.constraints.NotNull;

/** 領取一次性搶購 token 入參(設計文件第 9 節:POST /seckill/token)。 */
public record SeckillTokenRequest(

        @NotNull(message = "不可為空")
        Long ticketTypeId
) {
}
