package com.seckill.common.web;

import com.seckill.common.exception.BizCode;
import com.seckill.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全域例外處理:所有 controller 路徑上的例外都轉為統一回應格式 {code, message, data}。
 * 安全考量(fail-closed):未預期例外一律回泛用訊息,不外洩堆疊與內部細節。
 *
 * <p>注意:Spring Security filter 層(未認證 401 / 權限不足 403)發生在 DispatcherServlet 之前,
 * 不會進到這裡,改由 {@code RestAuthenticationEntryPoint} / {@code RestAccessDeniedHandler} 處理。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        log.warn("業務例外 code={} msg={}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getHttpStatus())
                .body(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String message = fieldError != null
                ? fieldError.getField() + " " + fieldError.getDefaultMessage()
                : BizCode.VALIDATION_FAILED.message();
        return ResponseEntity.status(BizCode.VALIDATION_FAILED.httpStatus())
                .body(ApiResponse.error(BizCode.VALIDATION_FAILED.code(), message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("未預期例外", ex);
        return ResponseEntity.status(BizCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.error(BizCode.INTERNAL_ERROR.code(), BizCode.INTERNAL_ERROR.message()));
    }
}
