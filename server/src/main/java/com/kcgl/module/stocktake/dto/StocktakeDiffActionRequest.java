package com.kcgl.module.stocktake.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 差异处理请求：CONFIRM=应用变更+STOCKTAKE_ADJUST 流水（幂等键契约同库存动作，
 * docs/01 7.0——失败重试复用同键，按键读回原结果 200 出清）；IGNORE=仅状态置位
 * （状态幂等：重复 IGNORE 观察到已置位即返回现状）。
 */
public record StocktakeDiffActionRequest(
        @NotBlank @Pattern(regexp = "CONFIRM|IGNORE") String action,
        @NotBlank String clientReqId) {
}
