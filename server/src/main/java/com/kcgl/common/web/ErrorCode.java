package com.kcgl.common.web;

/**
 * 全局错误码（docs/01 六节统一响应契约 {code,message,data}）。
 * M1 i18n 切片将把 message 换成 message key，code 数字是对外稳定契约不再变更。
 */
public enum ErrorCode {

    OK(0, "成功"),

    VALIDATION(400001, "入力内容に誤りがあります"),
    OLD_PASSWORD_MISMATCH(400101, "現在のパスワードが正しくありません"),
    PASSWORD_POLICY(400102, "新しいパスワードが条件を満たしていません"),

    UNAUTHENTICATED(401001, "ログインが必要です"),
    BAD_CREDENTIALS(401002, "ユーザー名またはパスワードが正しくありません"),
    ACCOUNT_DISABLED(401003, "このアカウントは無効になっています"),

    FORBIDDEN(403001, "この操作を行う権限がありません"),
    BAD_ORIGIN(403002, "許可されていないアクセス元です"),

    NOT_FOUND(404001, "対象が見つかりません"),

    ACCOUNT_LOCKED(423001, "アカウントがロックされました。しばらくしてからもう一度お試しください"),

    USER_EXISTS(409001, "同じユーザー名が既に存在します"),

    RATE_LIMITED(429001, "送信回数が上限を超えました。しばらくしてからもう一度お試しください"),

    INTERNAL(500000, "システムエラーが発生しました");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
