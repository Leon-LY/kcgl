package com.kcgl.module.dict.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 字典项停用/启用（只停用不物理删）。 */
public record StatusRequest(
        @NotNull @Min(0) @Max(1) Integer enabled) {
}
