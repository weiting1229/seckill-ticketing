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
    REFRESH_TOKEN_INVALID(1005, "refresh token 無效或已撤銷", HttpStatus.UNAUTHORIZED);

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
