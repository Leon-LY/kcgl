package com.kcgl.module.yahoo.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对账三活视图（docs/01 7.2 唯一定义，D-069 语义重校）：已成交未出库（受注表
 * 数据源）／流拍未重新出品（手动取消标记驱动）／已出库但雅虎仍在售=撤架清单
 * （手动上架标记驱动）。「受注有而系统无」不在此——并入每批导入报告 unmatched
 * 计数（含 raw_item_code 原文码）。
 */
public record ReconcileResponse(
        List<ReconcileRow> soldNotShipped,
        List<ReconcileRow> canceledNotRelisted,
        List<ReconcileRow> withdrawNeeded) {

    /**
     * 单行。orderId/auctionId/closedAt=受注记录留痕（无 listing 行时 null）；
     * lastSyncedAt=视图一/二=listing 行最后更新时刻，视图三（撤架）=最近一次
     * 受注导入完成时刻（受注表无在售信息，「仍标记在售」的事实新鲜度以最近导入
     * 为准——其间仍未成交即通过了一次校验）；delayed=滞留超阈值（红标）；
     * recentlySynced=阈值天数内同步过（降灰提示，主要作用于撤架视图）。
     */
    public record ReconcileRow(
            Long itemId, String itemCode, Integer warehouse, String shelfNo,
            Long soldPrice, String orderId, String auctionId, LocalDateTime closedAt,
            LocalDateTime lastSyncedAt, boolean delayed, boolean recentlySynced) {
    }
}
