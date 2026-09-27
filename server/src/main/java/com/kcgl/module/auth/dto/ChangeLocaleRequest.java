package com.kcgl.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 修改界面语言偏好（三语，DB CHECK 同域约束）。
 */
public record ChangeLocaleRequest(
        @NotBlank @Pattern(regexp = "^(ja-JP|zh-CN|en-US)$", message = "locale は ja-JP / zh-CN / en-US のいずれかです")
        String locale) {
}
