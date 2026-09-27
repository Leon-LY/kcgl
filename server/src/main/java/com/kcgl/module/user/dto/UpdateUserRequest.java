package com.kcgl.module.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理员改号：仅身份属性；username 不可改（流水与审计的操作人快照依赖其稳定性），
 * 密码变更走专用的 reset 端点（每次重置都是一次性初始密码）。
 */
public record UpdateUserRequest(
        @NotBlank @Size(max = 64) String displayName,
        @NotNull @Min(1) @Max(3) int role,
        @NotBlank @Pattern(regexp = "ja-JP|zh-CN|en-US") String locale) {
}
