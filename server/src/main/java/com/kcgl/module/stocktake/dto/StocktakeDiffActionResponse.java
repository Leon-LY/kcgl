package com.kcgl.module.stocktake.dto;

import com.kcgl.module.inventory.dto.ActionResult;

/** 差异处理结果：result 仅 CONFIRM 返回（STOCKTAKE_ADJUST 后的商品现态）。 */
public record StocktakeDiffActionResponse(
        StocktakeDiffRowResponse diff,
        ActionResult result) {
}
