package com.kcgl.module.stats;

import com.kcgl.common.web.ApiResponse;
import com.kcgl.module.stats.dto.DashboardStatsResponse;
import com.kcgl.module.stats.dto.WarehouseStatsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统计端点（docs/01 六节）：大盘（全仓合计+雅虎同步）与两仓明细，
 * 全角色可读（经营视角对全员开放，与列表/搜索同权限面）。纯只读无 SSE——
 * 大盘按页加载拉取，实时性由用户刷新承载（统计是聚合快照非协作面）。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/dashboard")
    public ApiResponse<DashboardStatsResponse> dashboard() {
        return ApiResponse.ok(statsService.dashboard());
    }

    @GetMapping("/warehouses")
    public ApiResponse<List<WarehouseStatsResponse>> warehouses() {
        return ApiResponse.ok(statsService.warehouses());
    }
}
