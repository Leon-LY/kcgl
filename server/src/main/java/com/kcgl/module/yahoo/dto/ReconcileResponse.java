package com.kcgl.module.yahoo.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对账三活视图（docs/01 7.2 唯一定义）：已成交未出库／流拍未重新出品／
 * 已出库但雅虎仍在售（撤架清单）。「CSV 有而系统无」不在此——并入每批
 * 导入报告 unmatched 计数（含 raw_item_code 原文码）。
 */
public record ReconcileResponse(
        List<ReconcileRow> soldNotShipped,
        List<ReconcileRow> canceledNotRelisted,
        List<ReconcileRow> withdrawNeeded) {

    /**
     * 单行。lastSyncedAt=listing 行最后更新时刻；delayed=滞留超阈值（红标，
     * 出荷待ち/重上逾期）；recentlySynced=阈值天数内同步过（降灰——CSV 滞后期
     * 的假阳性提示，主要作用于撤架视图）。
     */
    public record ReconcileRow(
            Long itemId, String itemCode, Integer warehouse, String shelfNo,
            Long soldPrice, String auctionId, LocalDateTime closedAt,
            LocalDateTime lastSyncedAt, boolean delayed, boolean recentlySynced) {
    }
}
