package com.kcgl.module.dict.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 档位增/改：左闭右开 [lower, upper)；NULL=无界端（首档无下限/末档无上限）。
 * 金额上限 99,999,999 円与商品表口径一致；区间重叠由服务层在行锁内校验。
 */
public record PriceBandUpsertRequest(
        @NotBlank @Pattern(regexp = "^[A-Z]$", message = "価格帯コードは半角英字1桁で入力してください") String code,
        @Min(0) @Max(99_999_999) Long lowerBound,
        @Min(1) @Max(99_999_999) Long upperBound) {

    public boolean isRangeInvalid() {
        return lowerBound != null && upperBound != null && lowerBound >= upperBound;
    }
}
