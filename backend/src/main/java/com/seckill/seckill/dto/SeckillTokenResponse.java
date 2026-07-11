package com.seckill.seckill.dto;

/**
 * 領取一次性搶購 token 回應。ticketTypeId 依專案慣例以 String 回傳(避免前端數字精度損失)。
 *
 * @param token             一次性 token,搶購時附帶
 * @param ticketTypeId      票種 ID(String)
 * @param expiresInSeconds  token 有效秒數(60)
 */
public record SeckillTokenResponse(String token, String ticketTypeId, long expiresInSeconds) {
}
