package com.seckill.auth.dto;

/** 目前登入者資訊(供受保護資源測試與前端顯示)。userId 以字串回傳避免精度損失。 */
public record MeResponse(
        String userId,
        String username,
        String role
) {
}
