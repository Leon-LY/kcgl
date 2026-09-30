package com.kcgl.module.yahoo.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批次不一致行明细（docs/01 7.4「受注有而系统无」）：批次报告此前只有 unmatched
 * <b>计数</b>，占位行（item_id NULL）在界面上无任何入口——甲方旧码（前系统手工码，
 * 非本系统管理号）导入后必然全是未匹配，用户只能看到一个 0 却拿不到任何线索。
 *
 * <p>归属语义：清单＝<b>本批次见过、且此刻仍未命中</b>的行（last_seen_batch_id 定位）。
 * 同一拍卖在后续导入中再被见到即改归后批，旧批次的清单随之让位——批次行上的
 * unmatched_count 是<b>当时的计数</b>（历史值，不等于历史清单）。
 *
 * <p>rows 上限 {@link #MAX_ROWS}：明细是给人看的线索不是数据导出（导出走 Excel 模块），
 * 超限截断并置 truncated——避免一个 10 万行全未匹配的批次把整表序列化进响应。
 */
public record UnmatchedRowsResponse(
        Long batchId, int total, boolean truncated, List<UnmatchedRow> rows) {

    /** 单批次明细返回上限（防大批次整表进响应）。 */
    public static final int MAX_ROWS = 200;

    /**
     * 单行：selfCode=展示用自码（**原文优先**——未匹配行的原文才是追查线索；
     * 原文缺失时回退归一码）；soldPrice=null 即まとめ売り（合计价无拆分依据，手填后补）。
     */
    public record UnmatchedRow(
            String selfCode, String orderId, String auctionId,
            Long soldPrice, LocalDateTime closedAt) {
    }
}
