package com.kcgl.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改自己的密码。最小 10 字符（docs/01 八节密码策略）；BCrypt 有效输入上限 72 字节。
 */
public record ChangePasswordRequest(
        @NotBlank String oldPassword,
        @NotBlank @Size(min = 10, max = 72) String newPassword) {
}
