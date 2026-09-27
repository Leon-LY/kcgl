package com.kcgl.module.item;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kcgl.common.audit.AuditRecorder;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.image.ImageEntity;
import com.kcgl.module.image.ImageMapper;
import com.kcgl.module.inventory.StockLedgerEntity;
import com.kcgl.module.inventory.StockLedgerMapper;
import com.kcgl.module.inventory.TxnType;
import com.kcgl.module.item.dto.ItemListResponse;
import com.kcgl.module.item.dto.ItemSummaryResponse;
import com.kcgl.module.item.dto.VoidItemRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品作废与查询（docs/01 7.1 作废重录全路径、7.2 VOID 行）。
 *
 * - 幂等（7.0）：clientReqId 先读回 VOID 流水，命中=重放返回原结果 200
 * - VOID 流水账规则（对账不变量）：在库件记该仓 −1；在途/已出库记 0（从未入账/已出账）
 * - 冻结：已作废件 409006；软删件按不存在 404（回收站视图 M5）
 * - 乐观锁：version 条件更新，并发变更（到仓/调拨在飞）时 409 快速失败重读
 * - 列表（M2-7 打印页）：创建日区间（JST 日界）+ 会场筛选，作废/软删件不出标签
 */
@Service
public class ItemService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ItemMapper itemMapper;
    private final StockLedgerMapper ledgerMapper;
    private final ImageMapper imageMapper;
    private final AuditRecorder auditRecorder;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    public ItemService(ItemMapper itemMapper, StockLedgerMapper ledgerMapper, ImageMapper imageMapper,
            AuditRecorder auditRecorder, TransactionTemplate txTemplate, Clock clock) {
        this.itemMapper = itemMapper;
        this.ledgerMapper = ledgerMapper;
        this.imageMapper = imageMapper;
        this.auditRecorder = auditRecorder;
        this.txTemplate = txTemplate;
        this.clock = clock;
    }

    /** 详情：作废件可见（重录预填/扫旧码提示前提）；软删件按不存在处理（回收站 M5）。 */
    public ItemEntity getById(long id) {
        ItemEntity item = itemMapper.selectById(id);
        if (item == null || (item.getDeleted() != null && item.getDeleted() == 1)) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return item;
    }

    public ItemEntity voidItem(long itemId, VoidItemRequest req, long operatorId, String operatorName) {
        ItemEntity replayed = findReplayedVoid(req.clientReqId());
        if (replayed != null) {
            return replayed;
        }
        return txTemplate.execute(status -> {
            ItemEntity item = requireLiveItem(itemId);
            LocalDateTime now = LocalDateTime.now(clock);
            int rows = itemMapper.update(null, new LambdaUpdateWrapper<ItemEntity>()
                    .eq(ItemEntity::getId, itemId)
                    .eq(ItemEntity::getVersion, item.getVersion())
                    .set(ItemEntity::getVoided, 1)
                    .set(ItemEntity::getVoidReason, req.reason())
                    .set(ItemEntity::getUpdatedBy, operatorId)
                    .set(ItemEntity::getUpdatedAt, now)
                    .set(ItemEntity::getVersion, item.getVersion() + 1));
            if (rows == 0) {
                // 并发窗口内商品被变更（version 前进）——快速失败，前端重读后再操作
                throw new BizException(ErrorCode.CONFLICT);
            }
            ledgerMapper.insert(buildVoidLedger(item, req, operatorId, operatorName, now));
            auditRecorder.record("ITEM_VOID", "item", itemId, Map.of(
                    "itemCode", item.getItemCode(),
                    "reason", req.reason(),
                    "stockStatus", item.getStockStatus()));
            return itemMapper.selectById(itemId);
        });
    }

    /**
     * 打印页列表（M2-7）：创建日区间（JST 日界：[from 00:00, to+1 00:00)）+ 可选会场，
     * 按录入顺序（id 升序）；作废/软删件不出标签（旧标签物理撕除，7.1）。
     * thumbUrl=每件首图缩略图（带缩略图标签排版用，无图为 null）。
     */
    public ItemListResponse listForPrint(LocalDate createdFrom, LocalDate createdTo,
            Long venueId, int page, int size) {
        if (createdFrom.isAfter(createdTo)) {
            throw new BizException(ErrorCode.VALIDATION, "作成日範囲の開始が終了より後になっています");
        }
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<ItemEntity> result = itemMapper.selectPage(new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<ItemEntity>()
                        .ge(ItemEntity::getCreatedAt, LocalDateTime.of(createdFrom, LocalTime.MIN))
                        .lt(ItemEntity::getCreatedAt, LocalDateTime.of(createdTo.plusDays(1), LocalTime.MIN))
                        .eq(venueId != null, ItemEntity::getVenueId, venueId)
                        .eq(ItemEntity::getVoided, 0)
                        .eq(ItemEntity::getDeleted, 0)
                        .orderByAsc(ItemEntity::getId));
        List<Long> itemIds = result.getRecords().stream().map(ItemEntity::getId).toList();
        Map<Long, String> firstThumbs = firstThumbByItem(itemIds);
        List<ItemSummaryResponse> rows = result.getRecords().stream()
                .map(item -> ItemSummaryResponse.from(item, firstThumbs.get(item.getId())))
                .toList();
        return new ItemListResponse(result.getTotal(), safePage, safeSize, rows);
    }

    /** 每件首图（sort_order 最小）缩略图 URL；空列表短路避免 IN ()。 */
    private Map<Long, String> firstThumbByItem(List<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        return imageMapper.selectList(new LambdaQueryWrapper<ImageEntity>()
                        .in(ImageEntity::getItemId, itemIds)
                        .orderByAsc(ImageEntity::getItemId)
                        .orderByAsc(ImageEntity::getSortOrder))
                .stream()
                .collect(Collectors.toMap(
                        ImageEntity::getItemId,
                        image -> "/img/thumb/" + image.getThumbPath(),
                        (first, later) -> first));
    }

    // ------------------------------------------------------------------ 内部

    /** 幂等读回：同 clientReqId 的 VOID 流水已存在=重放，返回原商品。 */
    private ItemEntity findReplayedVoid(String clientReqId) {
        if (clientReqId == null || clientReqId.isBlank()) {
            return null;
        }
        StockLedgerEntity ledger = ledgerMapper.selectOne(new LambdaQueryWrapper<StockLedgerEntity>()
                .eq(StockLedgerEntity::getClientReqId, clientReqId)
                .eq(StockLedgerEntity::getTxnType, TxnType.VOID.id()));
        if (ledger == null) {
            return null;
        }
        ItemEntity item = itemMapper.selectById(ledger.getItemId());
        if (item == null) {
            throw new BizException(ErrorCode.INTERNAL, "取り消し処理の再現に失敗しました");
        }
        return item;
    }

    private ItemEntity requireLiveItem(long itemId) {
        ItemEntity item = itemMapper.selectById(itemId);
        if (item == null || (item.getDeleted() != null && item.getDeleted() == 1)) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        if (item.getVoided() != null && item.getVoided() == 1) {
            throw new BizException(ErrorCode.ITEM_ALREADY_VOIDED);
        }
        return item;
    }

    /**
     * VOID 行：库存态冻结不迁移（stock_from=原值/stock_to=NULL）；在库件按对账不变量
     * 记 (该仓,−1) 双向入账的减侧，在途/已出库件不占仓账记 0。
     */
    private StockLedgerEntity buildVoidLedger(ItemEntity item, VoidItemRequest req,
            long operatorId, String operatorName, LocalDateTime now) {
        boolean inStock = item.getStockStatus() != null && item.getStockStatus() == 1;
        StockLedgerEntity ledger = new StockLedgerEntity();
        ledger.setClientReqId(req.clientReqId());
        ledger.setTxnType(TxnType.VOID.id());
        ledger.setItemId(item.getId());
        ledger.setItemCode(item.getItemCode());
        ledger.setStockFrom(item.getStockStatus());
        ledger.setWhFrom(inStock ? item.getWarehouse() : null);
        ledger.setQtyChange(inStock ? -1 : 0);
        ledger.setReason(req.reason());
        ledger.setOperatorId(operatorId);
        ledger.setOperatorName(operatorName);
        ledger.setCreatedAt(now);
        return ledger;
    }
}
