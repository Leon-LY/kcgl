package com.kcgl.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 全局异常 → 统一 JSON 信封。
 * 未处理异常记 ERROR 日志并生成 8 位 errorId：用户报 errorId，运维按 errorId 查日志（docs/01 9.3）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> onValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse(ErrorCode.VALIDATION.message());
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.VALIDATION, detail));
    }

    /**
     * 方法安全（@PreAuthorize）抛出的 AccessDeniedException 必须原样放行——
     * 被 @RestControllerAdvice 吞掉会变 500，正确语义是由 Security 过滤链翻译为 403。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public void onAccessDenied(AccessDeniedException ex) throws AccessDeniedException {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> onUnhandled(Exception ex) {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        String errorId = HexFormat.of().formatHex(bytes);
        log.error("[errorId={}] 未处理异常", errorId, ex);
        return ResponseEntity.internalServerError().body(ApiResponse.internal(errorId));
    }
}
