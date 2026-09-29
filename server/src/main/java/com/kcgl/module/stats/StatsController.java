package com.kcgl.module.stats;

import com.kcgl.common.web.ApiResponse;
import com.kcgl.module.stats.dto.DashboardStatsResponse;
import com.kcgl.module.stats.dto.SystemStatusResponse;
import com.kcgl.module.stats.dto.WarehouseStatsResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统计端点（docs/01 六节）：大盘（全仓合计+雅虎同步）与两仓明细，
 * 全角色可读（经营视角对全员开放，与列表/搜索同权限面）。纯只读无 SSE——
 * 大盘按页加载拉取，实时性由用户刷新承载（统计是聚合快照非协作面）。
 * システム状況（M5-④）=管理员专用（含池/磁盘等运行时内部信息）。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;
    private final SystemStatusService systemStatusService;

    public StatsController(StatsService statsService, SystemStatusService systemStatusService) {
        this.statsService = statsService;
        this.systemStatusService = systemStatusService;
    }

    @GetMapping("/dashboard")
    public ApiResponse<DashboardStatsResponse> dashboard() {
        return ApiResponse.ok(statsService.dashboard());
    }

    @GetMapping("/warehouses")
    public ApiResponse<List<WarehouseStatsResponse>> warehouses() {
        return ApiResponse.ok(statsService.warehouses());
    }

    /** システム状況（M5-④）：管理员排障速览，白名单字段。 */
    @GetMapping("/system")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<SystemStatusResponse> system() {
        return ApiResponse.ok(systemStatusService.status());
    }
}
