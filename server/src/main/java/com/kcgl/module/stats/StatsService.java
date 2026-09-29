package com.kcgl.module.stats;

import com.kcgl.module.inventory.StockLedgerMapper;
import com.kcgl.module.item.ItemMapper;
import com.kcgl.module.item.ItemStatsAggregate;
import com.kcgl.module.setting.SettingService;
import com.kcgl.module.stats.dto.DashboardStatsResponse;
import com.kcgl.module.stats.dto.WarehouseStatsResponse;
import com.kcgl.module.stats.dto.YahooStatsResponse;
import com.kcgl.module.yahoo.YahooImportBatchEntity;
import com.kcgl.module.yahoo.YahooReconcileService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 大盘/两仓统计（docs/01 六节，M5-③）：只读聚合，无事务。
 * 口径单点：item 侧条件聚合在 ItemMapper#aggregateStats、出库流水在
 * StockLedgerMapper#monthOutbound、雅虎计数复用三活视图条件
 * （YahooReconcileService）——同一谓词不写第二遍。月窗口/滞销边界按 JST
 * （注入 Clock），「今天」取一次全查询共用。
 */
@Service
public class StatsService {

    /** 固定两仓（V1 CHECK 约束 warehouse IN (1,2)：1=名古屋 2=福岡）。 */
    private static final List<Integer> WAREHOUSES = List.of(1, 2);

    private final ItemMapper itemMapper;
    private final StockLedgerMapper ledgerMapper;
    private final YahooReconcileService yahooReconcileService;
    private final SettingService settingService;
    private final Clock clock;

    public StatsService(ItemMapper itemMapper, StockLedgerMapper ledgerMapper,
            YahooReconcileService yahooReconcileService, SettingService settingService, Clock clock) {
        this.itemMapper = itemMapper;
        this.ledgerMapper = ledgerMapper;
        this.yahooReconcileService = yahooReconcileService;
        this.settingService = settingService;
        this.clock = clock;
    }

    public DashboardStatsResponse dashboard() {
        LocalDate today = LocalDate.now(clock);
        return new DashboardStatsResponse(
                row(null, today),
                yahooStats());
    }

    public List<WarehouseStatsResponse> warehouses() {
        LocalDate today = LocalDate.now(clock);
        return WAREHOUSES.stream()
                .map(warehouse -> row(warehouse, today))
                .toList();
    }

    // ------------------------------------------------------------- 行拼装

    private WarehouseStatsResponse row(Integer warehouse, LocalDate today) {
        SettingService.SlowMoveThresholds thresholds = settingService.slowMoveThresholds();
        LocalDate monthStart = today.withDayOfMonth(1);
        ItemStatsAggregate agg = itemMapper.aggregateStats(
                today,
                today.minusDays(thresholds.warnDays()),
                today.minusDays(thresholds.alarmDays()),
                monthStart,
                monthStart.plusMonths(1).minusDays(1),
                warehouse);
        long monthOutbound = ledgerMapper.monthOutbound(
                monthStart.atStartOfDay(), monthStart.plusMonths(1).atStartOfDay(), warehouse);
        return new WarehouseStatsResponse(
                warehouse,
                value(agg.getTotalItems()),
                value(agg.getInTransit()),
                value(agg.getInStock()),
                value(agg.getShipped()),
                value(agg.getMonthInbound()),
                monthOutbound,
                value(agg.getSlowWarn()),
                value(agg.getSlowRed()),
                value(agg.getStockValue()),
                agg.getAvgStockAgeDays());
    }

    private YahooStatsResponse yahooStats() {
        YahooImportBatchEntity latest = yahooReconcileService.latestDoneBatch();
        return new YahooStatsResponse(
                yahooReconcileService.countSoldNotShipped(),
                yahooReconcileService.countCanceledNotRelisted(),
                yahooReconcileService.countWithdrawNeeded(),
                latest == null ? null : latest.getFinishedAt(),
                latest == null ? null : latest.getOriginalFilename());
    }

    /** 聚合行恒非空（无 GROUP BY 的聚合查询空表也回一行），防御式归零。 */
    private static long value(Long count) {
        return count == null ? 0 : count;
    }
}
