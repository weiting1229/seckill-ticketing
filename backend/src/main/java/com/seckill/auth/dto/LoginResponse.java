package com.seckill.auth.dto;

/**
 * 登入回應。tokenType 固定 Bearer;expiresIn 為 access token 有效秒數。
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {
    public static LoginResponse of(String accessToken, String refreshToken, long expiresInSeconds) {
        return new LoginResponse(accessToken, refreshToken, "Bearer", expiresInSeconds);
    }
}
