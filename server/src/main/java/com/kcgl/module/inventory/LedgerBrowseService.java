package com.kcgl.module.inventory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kcgl.module.inventory.dto.LedgerBrowseResponse;
import org.springframework.stereotype.Service;

/**
 * 全库流水浏览（M5-④，docs/01 7.9 GET /inventory/ledgers）：只读治理查询。
 * 流水表只增不改不删——本服务零写路径；筛选项与列表页惯例一致
 * （等值/前缀 LIKE ESCAPE/时间闭开区间），排序恒 id 倒序保证稳定翻页。
 */
@Service
public class LedgerBrowseService {

    /** 分页上限与回收站/列表页对齐（防一次拉全表）。 */
    static final int MAX_PAGE_SIZE = 100;

    private final StockLedgerMapper ledgerMapper;

    public LedgerBrowseService(StockLedgerMapper ledgerMapper) {
        this.ledgerMapper = ledgerMapper;
    }

    public LedgerBrowseResponse browse(LedgerBrowseQuery query) {
        int safePage = Math.max(query.page(), 1);
        int safeSize = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
        LambdaQueryWrapper<StockLedgerEntity> wrapper = new LambdaQueryWrapper<StockLedgerEntity>()
                .eq(query.txnType() != null, StockLedgerEntity::getTxnType, query.txnType())
                .eq(query.itemId() != null, StockLedgerEntity::getItemId, query.itemId())
                // 管理号快照走前缀匹配：翻查场景按会场/年代号起头是常态
                .likeRight(query.itemCodePrefix() != null && !query.itemCodePrefix().isBlank(),
                        StockLedgerEntity::getItemCode, query.itemCodePrefix())
                .like(query.operatorName() != null && !query.operatorName().isBlank(),
                        StockLedgerEntity::getOperatorName, query.operatorName())
                // 仓库命中=流入或流出任一侧（调拨两侧、出入库单侧）
                .and(query.warehouse() != null, w -> w
                        .eq(StockLedgerEntity::getWhTo, query.warehouse())
                        .or()
                        .eq(StockLedgerEntity::getWhFrom, query.warehouse()))
                .ge(query.from() != null, StockLedgerEntity::getCreatedAt, query.from())
                .lt(query.to() != null, StockLedgerEntity::getCreatedAt, query.to())
                .orderByDesc(StockLedgerEntity::getId);
        Page<StockLedgerEntity> result = ledgerMapper.selectPage(Page.of(safePage, safeSize), wrapper);
        return new LedgerBrowseResponse(result.getTotal(), safePage, safeSize,
                result.getRecords().stream().map(LedgerBrowseService::row).toList());
    }

    private static LedgerBrowseResponse.Row row(StockLedgerEntity ledger) {
        return new LedgerBrowseResponse.Row(
                ledger.getId(),
                ledger.getItemId(),
                ledger.getItemCode(),
                ledger.getTxnType(),
                ledger.getWhFrom(),
                ledger.getWhTo(),
                ledger.getQtyChange(),
                ledger.getStockFrom(),
                ledger.getStockTo(),
                ledger.getSaleFrom(),
                ledger.getSaleTo(),
                ledger.getReturnDirection(),
                ledger.getRefType(),
                ledger.getRefId(),
                ledger.getReason(),
                ledger.getReasonCode(),
                ledger.getReasonParams(),
                ledger.getClientReqId(),
                ledger.getOperatorName(),
                ledger.getCreatedAt());
    }

    /**
     * 查询参数（全部可选）。时间区间 [from, to)——闭开与列表页一致，
     * 「到日」传次日零点由前端组装（服务端不猜「日含尾」语义）。
     */
    public record LedgerBrowseQuery(
            Integer txnType,
            String itemCodePrefix,
            String operatorName,
            Integer warehouse,
            Long itemId,
            java.time.LocalDateTime from,
            java.time.LocalDateTime to,
            int page,
            int size) {
    }
}
