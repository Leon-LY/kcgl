package com.kcgl.module.item.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单件流水（GET /api/items/{id}/ledgers，D-061）。
 * 按 id 倒序——毫秒精度时间戳并列时 id（写入序）决胜负。
 */
public record ItemLedgerListResponse(
        List<Row> rows) {

    public record Row(
            Long id,
            Integer txnType,
            Integer whFrom,
            Integer whTo,
            Integer qtyChange,
            Integer stockFrom,
            Integer stockTo,
            Integer saleFrom,
            Integer saleTo,
            String reason,
            String operatorName,
            LocalDateTime createdAt) {
    }
}
