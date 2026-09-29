package com.kcgl.module.item.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单件雅虎受注记录（GET /api/items/{id}/yahoo-listings，D-061/D-069），
 * closed_at（受注时刻）倒序。まとめ売り同拍卖多件=该商品仅见自己的行。
 */
public record YahooListingListResponse(
        List<Row> rows) {

    /** status：受注导入恒 2=落札済み；1/3 仅存在于 CSV 时代历史行（docs/01 5.3）。 */
    public record Row(
            Long id,
            String orderId,
            String yahooAuctionId,
            Long listPrice,
            Long soldPrice,
            Integer status,
            LocalDateTime listedAt,
            LocalDateTime closedAt) {
    }
}
