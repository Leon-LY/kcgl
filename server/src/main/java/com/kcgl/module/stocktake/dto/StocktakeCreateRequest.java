package com.kcgl.module.stocktake.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 发起盘点请求：选仓库（1名古屋 2福岡）；同仓已有进行中单 → 409009。 */
public record StocktakeCreateRequest(
        @NotNull @Min(1) @Max(2) Integer warehouse) {
}
