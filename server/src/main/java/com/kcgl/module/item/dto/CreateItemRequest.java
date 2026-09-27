package com.kcgl.module.item.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 录入请求（POST /api/items）。buyDate/photoDate 未来日校验在控制器按 JST Clock 执行
 * （@PastOrPresent 走系统默认时区，开发机 +08 与 JST 有日期边界差）。
 * 列长度与 V1 DDL 对齐（item_name 200 / category 64 / author_kiln 128 / size_text 64 / sales_channel 32）。
 */
public record CreateItemRequest(
        @Size(max = 36) String clientReqId,
        @NotNull @Positive Long venueId,
        @NotNull LocalDate buyDate,
        LocalDate photoDate,
        @NotNull @Min(1) @Max(99_999_999) Long purchasePrice,
        @Min(0) @Max(99_999_999) Long fee,
        @Min(0) @Max(99_999_999) Long shippingFee,
        @Min(0) @Max(99_999_999) Long tax,
        @NotNull @Min(1) @Max(2) Integer warehouse,
        @Size(max = 32) String shelfNo,
        LocalDate warehouseInDate,
        @Size(max = 32) String groupNo,
        @Size(max = 500) String remark,
        @Size(max = 200) String itemName,
        @Size(max = 64) String category,
        @Size(max = 128) String authorKiln,
        @Size(max = 64) String sizeText,
        @Min(1) @Max(2_000_000) Integer weightG,
        @Size(max = 32) String salesChannel) {
}
