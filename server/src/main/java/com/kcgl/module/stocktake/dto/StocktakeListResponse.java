package com.kcgl.module.stocktake.dto;

import java.util.List;

/** 盘点单列表（创建时间倒序分页）。 */
public record StocktakeListResponse(
        long total,
        int page,
        int size,
        List<StocktakeSummaryResponse> rows) {
}
