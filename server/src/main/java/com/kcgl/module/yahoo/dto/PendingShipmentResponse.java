package com.kcgl.module.yahoo.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 出荷待ち（发货待办清单，docs/01 六节）：已成交未出库——数天发货时滞是
 * 仓库日课的拣货队列而非异常，故独立于 reconcile 命名空间。按货架号排序
 * （null 押后）+首图缩略图；delayed=成交超阈值天数未出库（红标）。
 */
public record PendingShipmentResponse(int count, List<PendingShipmentRow> items) {

    public record PendingShipmentRow(
            Long itemId, String itemCode, String thumbUrl, Integer warehouse,
            String shelfNo, Long soldPrice, String orderId, String auctionId,
            LocalDateTime closedAt, boolean delayed) {
    }
}
