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
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

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

    /**
     * 上傳超過 {@code spring.servlet.multipart} 上限。
     *
     * <p>不接這個例外的話會落到下面的 catch-all,使用者拿到的是 9999「系統錯誤」——
     * 而這其實是參數問題,訊息裡連「檔案太大」四個字都沒有。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("上傳超過大小上限", ex);
        return ResponseEntity.status(BizCode.VALIDATION_FAILED.httpStatus())
                .body(ApiResponse.error(BizCode.VALIDATION_FAILED.code(),
                        "上傳內容超過大小上限"));
    }

    /** multipart 少了必要的 part(例如只送了圖沒送 manifest);同樣是參數問題不是系統錯誤。 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException ex) {
        return ResponseEntity.status(BizCode.VALIDATION_FAILED.httpStatus())
                .body(ApiResponse.error(BizCode.VALIDATION_FAILED.code(),
                        "缺少必要的 multipart part:" + ex.getRequestPartName()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("未預期例外", ex);
        return ResponseEntity.status(BizCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.error(BizCode.INTERNAL_ERROR.code(), BizCode.INTERNAL_ERROR.message()));
    }
}
