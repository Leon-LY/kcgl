package com.kcgl.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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
     * multipart 容器层粗筛超限（图片 5MB/CSV 50MB/Excel 20MB 各业务上限的余量 60MB）：
     * 进入业务校验前被 Tomcat 拒收。通用码（不区分业务——该层拿不到目标端点语义），
     * 文案不带数字（上限经 KCGL_MAX_FILE_SIZE 可调，写死会陈旧）；精确校验与专属文案在各业务 Service。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> onMaxUpload(MaxUploadSizeExceededException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.UPLOAD_TOO_LARGE));
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

    /**
     * 客户端输入无法绑定：缺参 / 参数类型不符 / 请求体不可解析（JSON 语法错误、字段类型
     * 对不上、数组当对象收等）。三者同属「请求形态不对」，原样落入 onUnhandled 会变
     * 500+errorId 并以 ERROR 级污染日志与告警口径——前端把自家拼错的请求体当系统故障报障，
     * 真故障反被淹没。WARN 留排查线索，响应用日文通文案（不透出 Spring 英文消息与原始 JSON）。
     */
    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> onClientInput(Exception ex) {
        log.warn("请求入力错误: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.VALIDATION));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> onUnhandled(Exception ex) {
        // errorId 复用当次请求 traceId（TraceIdFilter 已入 MDC）——用户报 errorId 即可检索
        // 该请求全部日志行；MDC 无值时兜底自生成（理论不可达，防御性）
        String errorId = org.slf4j.MDC.get("traceId");
        if (errorId == null || errorId.isBlank()) {
            byte[] bytes = new byte[4];
            RANDOM.nextBytes(bytes);
            errorId = HexFormat.of().formatHex(bytes);
        }
        log.error("[errorId={}] 未处理异常", errorId, ex);
        return ResponseEntity.internalServerError().body(ApiResponse.internal(errorId));
    }
}
