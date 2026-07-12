package com.seckill.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 業務錯誤碼(設計文件第 9 節:4 位數,1xxx 認證、2xxx 活動、3xxx 搶購、4xxx 訂單)。
 * 每個碼綁定預設訊息與對應 HTTP 狀態。code = 0 保留給成功,不在此列舉。
 */
public enum BizCode {

    // --- 通用 / 框架層 ---
    VALIDATION_FAILED(1400, "參數校驗失敗", HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(1401, "未認證或憑證無效", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(1403, "權限不足", HttpStatus.FORBIDDEN),
    INTERNAL_ERROR(9999, "系統錯誤", HttpStatus.INTERNAL_SERVER_ERROR),

    // --- 認證 1xxx ---
    USERNAME_ALREADY_EXISTS(1001, "使用者名稱已存在", HttpStatus.CONFLICT),
    INVALID_CREDENTIALS(1002, "帳號或密碼錯誤", HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_INVALID(1005, "refresh token 無效或已撤銷", HttpStatus.UNAUTHORIZED),

    // --- 活動 / 票種 2xxx ---
    EVENT_NOT_FOUND(2001, "活動不存在", HttpStatus.NOT_FOUND),
    EVENT_STATUS_INVALID(2002, "活動狀態轉移非法", HttpStatus.CONFLICT),
    EVENT_DELETE_FORBIDDEN(2003, "活動已含票種,不可刪除", HttpStatus.CONFLICT),
    TICKET_TYPE_NOT_FOUND(2004, "票種不存在", HttpStatus.NOT_FOUND),
    TICKET_TYPE_NOT_EDITABLE(2005, "票種已上線,不可修改或刪除", HttpStatus.CONFLICT),
    TICKET_TIME_RANGE_INVALID(2006, "搶購時間區間非法(開始須早於結束)", HttpStatus.BAD_REQUEST),

    // --- 搶購 3xxx ---
    SECKILL_NOT_STARTED(3001, "搶購尚未開始", HttpStatus.CONFLICT),
    SECKILL_ENDED(3002, "搶購已結束", HttpStatus.CONFLICT),
    SECKILL_TICKET_NOT_ONLINE(3003, "票種未上線", HttpStatus.CONFLICT),
    RATE_LIMITED(3004, "請求過於頻繁,請稍後再試", HttpStatus.TOO_MANY_REQUESTS),
    SECKILL_SOLD_OUT(3005, "票種已售罄", HttpStatus.CONFLICT),
    SECKILL_DUPLICATE_PURCHASE(3006, "您已購買過此票種(每人限購一張)", HttpStatus.CONFLICT),
    SECKILL_INVALID_TOKEN(3007, "搶購憑證無效或已使用,請重新領取", HttpStatus.FORBIDDEN),
    SECKILL_NOT_WARMED(3008, "票種尚未就緒", HttpStatus.CONFLICT),
    SECKILL_ENQUEUE_FAILED(3009, "系統忙碌,請稍後再試", HttpStatus.SERVICE_UNAVAILABLE);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    BizCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
