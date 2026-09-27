package com.kcgl.module.item.dto;

import com.kcgl.module.item.ItemEntity;

import java.time.LocalDate;

/**
 * 商品摘要（打印页列表用，M2-7）：标签只需管理号/落札日/会场码 + 可选缩略图 URL。
 * 完整字段走 GET /api/items/{id}。
 */
public record ItemSummaryResponse(
        Long id,
        String itemCode,
        LocalDate buyDate,
        String venueCode,
        String thumbUrl) {

    public static ItemSummaryResponse from(ItemEntity entity, String thumbUrl) {
        return new ItemSummaryResponse(
                entity.getId(),
                entity.getItemCode(),
                entity.getBuyDate(),
                entity.getVenueCode(),
                thumbUrl);
    }
}
