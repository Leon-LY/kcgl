package com.kcgl.module.stocktake.dto;

import java.util.List;

/** 差异表分页（待确认优先：confirm_status 升序）。 */
public record StocktakeDiffListResponse(
        long total,
        int page,
        int size,
        List<StocktakeDiffRowResponse> rows) {
}
