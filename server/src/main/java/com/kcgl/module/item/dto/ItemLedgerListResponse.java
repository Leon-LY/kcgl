package com.kcgl.module.item.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单件流水（GET /api/items/{id}/ledgers，D-061）。
 * 按 id 倒序——毫秒精度时间戳并列时 id（写入序）决胜负。
 *
 * <p>reasonCode/reasonParams（V7，D-130）是系统生成理由的结构化形态（JSON 文本）：
 * 非空时前端按当前语言渲染，为空即人工理由或历史行，回退 reason 原文。
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
            String reasonCode,
            String reasonParams,
            String operatorName,
            LocalDateTime createdAt) {
    }
}
