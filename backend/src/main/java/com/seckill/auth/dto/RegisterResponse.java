package com.seckill.auth.dto;

/** 註冊回應。userId 以字串回傳,避免 64-bit Snowflake ID 在前端 JS 損失精度。 */
public record RegisterResponse(
        String userId,
        String username
) {
}
