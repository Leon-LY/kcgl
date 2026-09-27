package com.kcgl.module.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理员建号：username 限登录名用拉丁字符（日文姓名走 displayName），
 * 初始密码由管理员设定且与管理端重置同策略（≥10 字符，首登强制改密）。
 */
public record CreateUserRequest(
        @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_-]{3,32}$",
                message = "ユーザー名は半角英数字と - _ のみ（3〜32字）で入力してください") String username,
        @NotBlank @Size(max = 64) String displayName,
        @NotNull @Min(1) @Max(3) int role,
        @NotBlank @Pattern(regexp = "ja-JP|zh-CN|en-US") String locale,
        @NotBlank @Size(min = 10, max = 72, message = "初期パスワードは10文字以上で入力してください") String password) {
}
