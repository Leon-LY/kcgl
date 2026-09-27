package com.kcgl.module.inventory.dto;

import com.kcgl.module.item.ItemEntity;

import java.util.List;

/** 确认入库结果：arrivedCount=本次实际入库+幂等重放读回的行数（重放不再产生第二条流水）。 */
public record ArrivalResponse(int arrivedCount, List<ArrivedItem> items) {

    public record ArrivedItem(long itemId, String itemCode, int stockStatus, int warehouse) {

        public static ArrivedItem from(ItemEntity item) {
            return new ArrivedItem(item.getId(), item.getItemCode(),
                    item.getStockStatus(), item.getWarehouse());
        }
    }
}
