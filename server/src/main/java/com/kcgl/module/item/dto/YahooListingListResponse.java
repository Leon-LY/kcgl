package com.kcgl.module.item.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 单件雅虎出品记录（GET /api/items/{id}/yahoo-listings，D-061），listed_at 倒序。 */
public record YahooListingListResponse(
        List<Row> rows) {

    /** status：1=出品中 2=落札済み 3=取消（雅虎管线口径）。 */
    public record Row(
            Long id,
            String yahooAuctionId,
            Long listPrice,
            Long soldPrice,
            Integer status,
            LocalDateTime listedAt,
            LocalDateTime closedAt) {
    }
}
