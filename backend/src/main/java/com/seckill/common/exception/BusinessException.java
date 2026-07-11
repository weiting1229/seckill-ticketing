package com.seckill.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 業務例外:controller / service 一律拋此例外,由全域處理器轉為統一回應格式。
 * 禁止在 controller 內 try-catch 吞例外(見 CLAUDE.md)。
 */
public class BusinessException extends RuntimeException {

    private final int code;
    private final HttpStatus httpStatus;

    public BusinessException(BizCode bizCode) {
        this(bizCode, bizCode.message());
    }

    /** 需要自訂訊息時使用(仍沿用該 BizCode 的 code 與 HTTP 狀態)。 */
    public BusinessException(BizCode bizCode, String message) {
        super(message);
        this.code = bizCode.code();
        this.httpStatus = bizCode.httpStatus();
    }

    public int getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
