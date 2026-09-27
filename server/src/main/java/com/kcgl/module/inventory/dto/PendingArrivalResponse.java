package com.kcgl.module.inventory.dto;

import com.kcgl.module.item.ItemEntity;

import java.time.LocalDate;
import java.util.List;

/** 在途清单（到货核对页）：卡片=缩略图+管理号+落札日+预计仓库。 */
public record PendingArrivalResponse(long total, int page, int size, List<Row> rows) {

    public record Row(long id, String itemCode, LocalDate buyDate, String thumbUrl, int warehouse) {

        public static Row from(ItemEntity item, String thumbUrl) {
            return new Row(item.getId(), item.getItemCode(), item.getBuyDate(),
                    thumbUrl, item.getWarehouse());
        }
    }
}
