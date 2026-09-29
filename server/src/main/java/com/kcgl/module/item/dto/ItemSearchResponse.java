package com.kcgl.module.item.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 全局搜索响应（GET /api/items/search，D-061/D-062/D-065）。
 * size 回显钳制后值（上限 100）；排除作废/软删件。
 */
public record ItemSearchResponse(
        long total,
        int page,
        int size,
        List<Row> rows) {

    /** slowMoveLevel：0=非滞销 1=黄（warnDays≤日差）2=红（日差≥alarmDays）——与 warnLevel 筛选同源边界。 */
    public record Row(
            Long id,
            String itemCode,
            String thumbUrl,
            String itemName,
            String venueName,
            LocalDate buyDate,
            Long purchasePrice,
            Long totalCost,
            Long profit,
            Integer warehouse,
            Integer stockStatus,
            Integer saleStatus,
            Long soldPrice,
            String shelfNo,
            LocalDate warehouseInDate,
            Integer slowMoveLevel) {
    }
}
