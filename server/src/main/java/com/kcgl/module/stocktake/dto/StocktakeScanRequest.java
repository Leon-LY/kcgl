package com.kcgl.module.stocktake.dto;

import jakarta.validation.constraints.NotBlank;

/** 盘点扫码请求：管理号（全角/小写容错=NFKC+大文字化，同 by-code 口径）。 */
public record StocktakeScanRequest(
        @NotBlank String code) {
}
