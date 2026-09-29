package com.kcgl.module.stats.dto;

/**
 * 单仓/全仓运营指标行（docs/01 六节：在库/在途/已出库数、本月入库/出库、
 * 滞销黄/红数、在库货值 ∑total_cost、件均库龄）。warehouse=null 表示全仓合计
 * （大盘 fleet 行）；件均库龄在无在库样本时为 null（前端显示「—」）。
 */
public record WarehouseStatsResponse(
        Integer warehouse,
        long totalItems,
        long inTransit,
        long inStock,
        long shipped,
        long monthInbound,
        long monthOutbound,
        long slowWarn,
        long slowRed,
        long stockValue,
        Double avgStockAgeDays) {
}
