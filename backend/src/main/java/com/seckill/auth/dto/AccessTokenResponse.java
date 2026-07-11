package com.seckill.auth.dto;

/** refresh 換發回應,僅回新的 access token。 */
public record AccessTokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {
    public static AccessTokenResponse of(String accessToken, long expiresInSeconds) {
        return new AccessTokenResponse(accessToken, "Bearer", expiresInSeconds);
    }
}
