package com.seckill.seckill.dto;

/**
 * 搶購受理回應。搶購成功入列即回,實際落庫為非同步——前端以 requestId 輪詢
 * {@code GET /seckill/result/{requestId}} 取最終結果。ID 一律以 String 回傳。
 *
 * @param requestId 排隊請求 ID(輪詢用)
 * @param orderId   預先產生的訂單號(落庫成功後即為此號)
 */
public record PurchaseResponse(String requestId, String orderId) {
}
