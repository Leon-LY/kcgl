package com.kcgl.module.stats.dto;

/**
 * 大盘响应（GET /api/stats/dashboard）：全仓合计行 + 雅虎同步卡。
 * 两仓明细走 GET /api/stats/warehouses（docs/01 六节两端点分工）。
 */
public record DashboardStatsResponse(
        WarehouseStatsResponse fleet,
        YahooStatsResponse yahoo) {
}
