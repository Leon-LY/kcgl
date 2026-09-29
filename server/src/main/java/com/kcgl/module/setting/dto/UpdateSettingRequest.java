package com.kcgl.module.setting.dto;

import jakarta.validation.constraints.NotNull;

/**
 * PUT /api/settings/{key} 请求体：值为字符串（sys_setting 值列即文本），
 * 具体格式校验在 SettingService 按键进行（空串/非数=400017）。
 */
public record UpdateSettingRequest(@NotNull String value) {
}
