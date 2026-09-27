package com.kcgl.module.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 停用/启用：1=启用 0=停用（停用对既有会话下一请求立即生效，见 AccountStatusFilter）。 */
public record UserStatusRequest(@NotNull @Min(0) @Max(1) Integer enabled) {
}
