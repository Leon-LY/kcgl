package com.kcgl.module.item.dto;

import com.kcgl.module.item.ItemEntity;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 本日录入会话（M2-8b 收工对数）：created_by=me + 当天 JST 的全部录入
 * （含作废件——对数口径：写错作废重录的件也要数进去）。
 */
public record TodaySessionResponse(LocalDate date, long activeCount, long voidedCount, List<Row> rows) {

    private static final DateTimeFormatter TIME_HM = DateTimeFormatter.ofPattern("HH:mm");

    /** 一行=一件：大字管理号 + 缩略图 + 作废标记（理由）+ 录入时刻。 */
    public record Row(long id, String itemCode, boolean voided, String voidReason,
            String createdAt, String thumbUrl) {

        public static Row from(ItemEntity item, String thumbUrl) {
            boolean isVoided = item.getVoided() != null && item.getVoided() == 1;
            return new Row(item.getId(), item.getItemCode(), isVoided, item.getVoidReason(),
                    item.getCreatedAt().format(TIME_HM), thumbUrl);
        }
    }
}
