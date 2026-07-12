package com.seckill.seckill.dto;

/**
 * 輪詢排隊結果回應(設計文件第 9 節:GET /seckill/result/{requestId})。
 *
 * @param status  QUEUING(仍在排隊,無結果)/ SUCCESS(已落庫)/ FAIL(落庫失敗)
 * @param orderId SUCCESS 時的訂單號,否則 null
 * @param reason  FAIL 時的原因,否則 null
 */
public record SeckillResultResponse(String status, String orderId, String reason) {

    public static final String QUEUING = "QUEUING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAIL = "FAIL";

    public static SeckillResultResponse queuing() {
        return new SeckillResultResponse(QUEUING, null, null);
    }

    public static SeckillResultResponse success(String orderId) {
        return new SeckillResultResponse(SUCCESS, orderId, null);
    }

    public static SeckillResultResponse fail(String reason) {
        return new SeckillResultResponse(FAIL, null, reason);
    }
}
