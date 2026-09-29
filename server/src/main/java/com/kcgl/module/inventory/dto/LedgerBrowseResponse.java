package com.kcgl.module.inventory.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 全库流水浏览（GET /api/inventory/ledgers，M5-④）：管理员治理视角的台账翻查。
 * id 倒序（写入序，毫秒并列时 id 决胜负）；行结构对齐单件流水 D-061，
 * 追加浏览维度（itemCode 快照/ref 指向/clientReqId）。
 */
public record LedgerBrowseResponse(
        long total,
        int page,
        int size,
        List<Row> rows) {

    public record Row(
            Long id,
            Long itemId,
            String itemCode,
            Integer txnType,
            Integer whFrom,
            Integer whTo,
            Integer qtyChange,
            Integer stockFrom,
            Integer stockTo,
            Integer saleFrom,
            Integer saleTo,
            Integer returnDirection,
            String refType,
            Long refId,
            String reason,
            String clientReqId,
            String operatorName,
            LocalDateTime createdAt) {
    }
}
