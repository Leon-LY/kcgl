package com.kcgl.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
     * 业务异常：HTTP 状态取 ErrorCode 前三位（400001→400 / 404001→404 / 409001→409），
     * 契约见 BizException。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> onBiz(BizException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.errorCode().code() / 1000);
        return ResponseEntity.status(status)
                .body(ApiResponse.error(ex.errorCode(), ex.getMessage()));
    }

    /**
     * 方法安全（@PreAuthorize）抛出的 AccessDeniedException 必须原样放行——
     * 被 @RestControllerAdvice 吞掉会变 500，正确语义是由 Security 过滤链翻译为 403。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public void onAccessDenied(AccessDeniedException ex) throws AccessDeniedException {
        throw ex;
    }

    /**
     * 无 handler 的路径（Spring 6.1+ 抛 NoResourceFoundException）必须映射 404——
     * 落入 onUnhandled 会把打错的 URL 变成 500 并污染 ERROR 日志。
     * 走统一信封而非 rethrow，保证前端只见一种错误格式。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> onNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ErrorCode.NOT_FOUND));
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
