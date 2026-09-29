package com.kcgl.module.item.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 商品编辑请求（PUT /api/items/{id}，D-063 snapshot 单模式）。
 *
 * 全量语义：可选字段缺省=null 即清空（补 D-035「重录无法清空费用」缺口）；
 * 号内快照列（venue_code/buy_month/seq_*）恒不变，管理号不重算。
 * 仓库契约 W（D-066）：非在途时仓值变化 → 409013 引导 /inventory/transfer；同值放行。
 * version=乐观锁（409 前端重读后按新 version 再提交）。
 */
public record UpdateItemRequest(
        @NotNull Integer version,
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
