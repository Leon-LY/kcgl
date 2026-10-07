package com.kcgl.module.item.dto;

import com.kcgl.module.item.ItemEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 商品视图（录入/详情共用；雅虎/回收站字段随对应里程碑扩展）。
 *
 * <p>尾三字段是作废重录互链的**对端管理号**（D-131）：re_entry_of / void_re_entry
 * 两个列存的都是 id，前端要显示号才能互相对上。仅详情端点装配（
 * {@code ItemService.detailOf}）——列表与批量端点不查，避免 N+1；未装配时三者
 * 均为 null，前端据此不渲染互链行。
 */
public record ItemResponse(
        Long id,
        String itemCode,
        Long venueId,
        String venueCode,
        Integer buyMonth,
        String seqPrefix,
        Integer seqNo,
        LocalDate buyDate,
        LocalDate photoDate,
        Long purchasePrice,
        Long fee,
        Long shippingFee,
        Long tax,
        Long soldPrice,
        Long totalCost,
        Long profit,
        String priceBandCode,
        Integer warehouse,
        String shelfNo,
        LocalDate warehouseInDate,
        String groupNo,
        String remark,
        String itemName,
        String category,
        String authorKiln,
        String sizeText,
        Integer weightG,
        String salesChannel,
        Integer stockStatus,
        Integer saleStatus,
        boolean voided,
        String voidReason,
        Long reEntryOf,
        boolean deleted,
        Integer version,
        LocalDateTime createdAt,
        Long voidReEntry,
        String reEntryOfCode,
        String voidReEntryCode) {

    public static ItemResponse from(ItemEntity e) {
        return new ItemResponse(
                e.getId(), e.getItemCode(), e.getVenueId(), e.getVenueCode(),
                e.getBuyMonth(), e.getSeqPrefix(), e.getSeqNo(),
                e.getBuyDate(), e.getPhotoDate(),
                e.getPurchasePrice(), e.getFee(), e.getShippingFee(), e.getTax(),
                e.getSoldPrice(), e.getTotalCost(), e.getProfit(),
                e.getPriceBandCode(), e.getWarehouse(), e.getShelfNo(), e.getWarehouseInDate(),
                e.getGroupNo(), e.getRemark(),
                e.getItemName(), e.getCategory(), e.getAuthorKiln(), e.getSizeText(),
                e.getWeightG(), e.getSalesChannel(),
                e.getStockStatus(), e.getSaleStatus(),
                e.getVoided() != null && e.getVoided() == 1,
                e.getVoidReason(),
                e.getReEntryOf(),
                e.getDeleted() != null && e.getDeleted() == 1,
                e.getVersion(),
                e.getCreatedAt(),
                e.getVoidReEntry(),
                null, null);
    }

    /** 详情装配：补上互链对端管理号（两列存的都是 id）。 */
    public ItemResponse withReEntryCodes(String sourceItemCode, String reEnteredItemCode) {
        return new ItemResponse(id, itemCode, venueId, venueCode, buyMonth, seqPrefix, seqNo,
                buyDate, photoDate, purchasePrice, fee, shippingFee, tax, soldPrice, totalCost,
                profit, priceBandCode, warehouse, shelfNo, warehouseInDate, groupNo, remark,
                itemName, category, authorKiln, sizeText, weightG, salesChannel, stockStatus,
                saleStatus, voided, voidReason, reEntryOf, deleted, version, createdAt,
                voidReEntry, sourceItemCode, reEnteredItemCode);
    }
}
