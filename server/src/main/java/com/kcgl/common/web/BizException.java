package com.kcgl.common.web;

/**
 * 业务异常：携带 ErrorCode，由 GlobalExceptionHandler 映射为统一 JSON 信封。
 * HTTP 状态约定：ErrorCode.code 前三位即 HTTP status（400001→400、404001→404、409001→409）。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    /** 覆盖默认消息的场景（如自锁护栏的具体提示）。 */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
