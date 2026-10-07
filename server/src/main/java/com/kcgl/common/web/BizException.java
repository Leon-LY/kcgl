package com.kcgl.common.web;

import com.kcgl.common.i18n.Msg;

/**
 * 业务异常：携带 ErrorCode，由 GlobalExceptionHandler 映射为统一 JSON 信封。
 * HTTP 状态约定：ErrorCode.code 前三位即 HTTP status（400001→400、404001→404、409001→409）。
 *
 * <p>{@code structured} 是**可选**的结构化提示：仅当该异常的消息会被**落库或跨语言
 * 展示**（导入批次报告的 error_message）时才带上，供前端按当前语言渲染。
 * 即时错误（直接进 API 信封的那类）不带它——前端已由 {@code errors.<code>} 覆盖，
 * 具体原因被泛化是另一条议题，不在此处扩散。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Msg structured;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.message(), null);
    }

    /** 覆盖默认消息的场景（如自锁护栏的具体提示）。 */
    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    /** @param structured 可空；带消息键+参数的版本，前端按当前语言渲染而非直出 message。 */
    public BizException(ErrorCode errorCode, String message, Msg structured) {
        super(message);
        this.errorCode = errorCode;
        this.structured = structured;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 结构化提示（可空）。 */
    public Msg structured() {
        return structured;
    }
}
