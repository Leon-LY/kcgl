package com.kcgl.module.dict.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 年代号增/改：年份与代号双向唯一；范围 2016-2999（V1 种子 2016=A 至 2041=Z，增改供 Z 用尽后扩展）。 */
public record YearCodeUpsertRequest(
        @NotNull @Min(2016) @Max(2999) Integer year,
        @NotBlank @Pattern(regexp = "^[A-Z]$", message = "年代号は半角英字1桁で入力してください") String code) {
}
