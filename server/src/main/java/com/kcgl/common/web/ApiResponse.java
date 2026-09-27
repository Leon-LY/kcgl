package com.kcgl.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应信封（docs/01 patterns：success + data + error message 三态合一）。
 * errorId 仅系统错误（500000）时非空，用于用户报障与日志比对（docs/01 9.3 节闭环）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(int code, String message, T data, String errorId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.code(), ErrorCode.OK.message(), data, null);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(ErrorCode.OK.code(), ErrorCode.OK.message(), null, null);
    }

    public static <T> ApiResponse<T> error(ErrorCode ec) {
        return new ApiResponse<>(ec.code(), ec.message(), null, null);
    }

    public static <T> ApiResponse<T> error(ErrorCode ec, String messageOverride) {
        return new ApiResponse<>(ec.code(), messageOverride, null, null);
    }

    public static <T> ApiResponse<T> error(ErrorCode ec, T data) {
        return new ApiResponse<>(ec.code(), ec.message(), data, null);
    }

    public static <T> ApiResponse<T> internal(String errorId) {
        return new ApiResponse<>(ErrorCode.INTERNAL.code(), ErrorCode.INTERNAL.message(), null, errorId);
    }
}
