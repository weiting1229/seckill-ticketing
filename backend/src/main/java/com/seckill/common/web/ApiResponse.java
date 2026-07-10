package com.seckill.common.web;

/**
 * 統一回應格式:{ "code": 0, "message": "ok", "data": { ... } }。
 * code = 0 表示成功;業務錯誤碼為 4 位數(1xxx 認證、2xxx 活動、3xxx 搶購、4xxx 訂單)。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
