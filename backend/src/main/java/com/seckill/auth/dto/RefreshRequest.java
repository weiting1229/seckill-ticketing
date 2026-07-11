package com.seckill.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(

        @NotBlank(message = "不可為空")
        String refreshToken
) {
}
