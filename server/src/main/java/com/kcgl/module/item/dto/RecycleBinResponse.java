package com.kcgl.module.item.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 回收站列表（GET /api/items/recycle-bin，D-064，管理员专用）。deleted_at 倒序。 */
public record RecycleBinResponse(
        long total,
        int page,
        int size,
        List<Row> rows) {

    /** reason=最近一次软删流水的原因；voided 与 deleted 两治理轴正交展示。 */
    public record Row(
            Long id,
            String itemCode,
            String thumbUrl,
            String itemName,
            String venueName,
            Integer warehouse,
            Integer stockStatus,
            Integer saleStatus,
            boolean voided,
            LocalDateTime deletedAt,
            String reason) {
    }
}
