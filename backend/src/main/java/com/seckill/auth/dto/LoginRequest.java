package com.seckill.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(

        @NotBlank(message = "不可為空")
        String username,

        @NotBlank(message = "不可為空")
        String password
) {
}
