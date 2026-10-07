package com.kcgl.module.item;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kcgl.common.audit.AuditRecorder;
import com.kcgl.common.sse.SseHub;
import com.kcgl.common.util.CodeNormalizer;
import com.kcgl.common.sse.SyncEvent;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.image.FirstThumbReader;
import com.kcgl.module.inventory.StockLedgerEntity;
import com.kcgl.module.inventory.StockLedgerMapper;
import com.kcgl.module.inventory.TxnType;
import com.kcgl.module.item.dto.ItemByCodeResponse;
import com.kcgl.module.item.dto.ItemListResponse;
import com.kcgl.module.item.dto.ItemResponse;
import com.kcgl.module.item.dto.ItemSummaryResponse;
import com.kcgl.module.item.dto.TodaySessionResponse;
import com.kcgl.module.item.dto.VoidItemRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    /** 重录反链最大跳数（防脏数据成环；正常深度 1-2 跳）。 */
    private static final int MAX_RE_ENTRY_HOPS = 10;
    /** 导出 keyset 分页批大小（forEachItemForExport）。 */
    private static final int EXPORT_PAGE_SIZE = 500;

    private final ItemMapper itemMapper;
    private final StockLedgerMapper ledgerMapper;
    private final FirstThumbReader firstThumbReader;
    private final AuditRecorder auditRecorder;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final SseHub sseHub;

    public ItemService(ItemMapper itemMapper, StockLedgerMapper ledgerMapper, FirstThumbReader firstThumbReader,
            AuditRecorder auditRecorder, TransactionTemplate txTemplate, Clock clock, SseHub sseHub) {
        this.itemMapper = itemMapper;
        this.ledgerMapper = ledgerMapper;
        this.firstThumbReader = firstThumbReader;
        this.auditRecorder = auditRecorder;
        this.txTemplate = txTemplate;
        this.clock = clock;
        this.sseHub = sseHub;
    }

    /** 详情：作废件可见（重录预填/扫旧码提示前提）；软删件按不存在处理（回收站 M5）。 */
    public ItemEntity getById(long id) {
        ItemEntity item = itemMapper.selectById(id);
        if (item == null || (item.getDeleted() != null && item.getDeleted() == 1)) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return item;
    }

    /**
     * 详情响应装配（D-131）：作废重录互链两列（re_entry_of / void_re_entry）存的都是
     * id，这里补对端管理号——前端要显示号才能把新旧件对上（此前只能从备注里的日文
     * 标记读，见 ItemCodeTxService）。仅互链非空才各查一次，普通件零额外查询；对端
     * 已不存在（异常数据）留 null，前端据此不渲染该行。
     */
    public ItemResponse detailOf(long id) {
        ItemEntity item = getById(id);
        return ItemResponse.from(item)
                .withReEntryCodes(codeOf(item.getReEntryOf()), codeOf(item.getVoidReEntry()));
    }

    private String codeOf(Long itemId) {
        if (itemId == null) {
            return null;
        }
        ItemEntity item = itemMapper.selectById(itemId);
        return item == null ? null : item.getItemCode();
    }

    /**
     * 扫码定位（M3-⑤，docs/01 六节 by-code 行）：管理号 NFKC+大文字化容错
     * （全角/小写手输兜底，与打印页単票再印刷同一归一规则）。
     * 作废/软删件同样返回（404 仅限「号不存在」）——deleted/voided 标志由前端按角色
     * 处置：非管理员见提示禁操作、管理员见回收站/作废态；作废件顺 void_re_entry
     * 反链给出重录新号（docs/01 7.1「扫旧码必须能查到新号」）。
     */
    public ItemByCodeResponse byCode(String code) {
        String normalized = Normalizer.normalize(code.trim(), Normalizer.Form.NFKC)
                .toUpperCase(Locale.ROOT);
        ItemEntity item = itemMapper.selectOne(new LambdaQueryWrapper<ItemEntity>()
                .eq(ItemEntity::getItemCode, normalized));
        if (item == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        String thumbUrl = firstThumbReader.byItemIds(List.of(item.getId())).get(item.getId());
        return new ItemByCodeResponse(ItemResponse.from(item), thumbUrl, followReEntry(item));
    }

    /**
     * 作废重录链：沿 void_re_entry 反链逐跳到尽头（新件再作废再重录时链自然延伸）。
     * 已作废未重录/反链悬空（异常数据）返回 null——不阻断扫码主流程。
     */
    private ItemByCodeResponse.ReEntry followReEntry(ItemEntity item) {
        ItemEntity cursor = item;
        for (int hop = 0; hop < MAX_RE_ENTRY_HOPS
                && cursor.getVoided() != null && cursor.getVoided() == 1; hop++) {
            if (cursor.getVoidReEntry() == null) {
                return null;
            }
            ItemEntity next = itemMapper.selectById(cursor.getVoidReEntry());
            if (next == null) {
                return null;
            }
            cursor = next;
        }
        return cursor == item ? null
                : new ItemByCodeResponse.ReEntry(cursor.getId(), cursor.getItemCode());
    }

    /**
     * 本日录入会话（M2-8b）：created_by=me + 当天 JST（[今日 00:00, 明日 00:00)），
     * 含作废件（收工对数口径——写错作废重录的件也数进「取り消し」）。
     * 不分页：对数为个人日清单，现实量级为每日数十件。
     */
    public TodaySessionResponse todaySession(long userId) {
        LocalDate today = LocalDate.now(clock);
        List<ItemEntity> items = itemMapper.selectList(new LambdaQueryWrapper<ItemEntity>()
                .eq(ItemEntity::getCreatedBy, userId)
                .ge(ItemEntity::getCreatedAt, LocalDateTime.of(today, LocalTime.MIN))
                .lt(ItemEntity::getCreatedAt, LocalDateTime.of(today.plusDays(1), LocalTime.MIN))
                .orderByAsc(ItemEntity::getId));
        long voidedCount = items.stream()
                .filter(item -> item.getVoided() != null && item.getVoided() == 1)
                .count();
        Map<Long, String> thumbs = firstThumbReader.byItemIds(
                items.stream().map(ItemEntity::getId).toList());
        List<TodaySessionResponse.Row> rows = items.stream()
                .map(item -> TodaySessionResponse.Row.from(item, thumbs.get(item.getId())))
                .toList();
        return new TodaySessionResponse(today, items.size() - voidedCount, voidedCount, rows);
    }

    public ItemEntity voidItem(long itemId, VoidItemRequest req, long operatorId, String operatorName) {
        ItemEntity replayed = findReplayedVoid(req.clientReqId());
        if (replayed != null) {
            return replayed;
        }
        ItemEntity voided = txTemplate.execute(status -> {
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
        // 提交后广播：作废冻结对他端可见（在途清单出清、本日会话「取り消し」计数变化）。
        // 重放读回早退于上方——原作废已广播过，重放不再发第二条
        sseHub.broadcast(SyncEvent.TYPE_ITEM, voided.getItemCode(), operatorId);
        return voided;
    }

    /**
     * 打印页列表（M2-7）：创建日区间（JST 日界：[from 00:00, to+1 00:00)）+ 可选会场，
     * 按录入顺序（id 升序）；作废/软删件不出标签（旧标签物理撕除，7.1）。
     * thumbUrl=每件首图缩略图（带缩略图标签排版用，无图为 null）。
     */
    public ItemListResponse listForPrint(LocalDate createdFrom, LocalDate createdTo,
            Long venueId, String code, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        LambdaQueryWrapper<ItemEntity> wrapper = new LambdaQueryWrapper<ItemEntity>()
                .eq(ItemEntity::getVoided, 0)
                .eq(ItemEntity::getDeleted, 0)
                .orderByAsc(ItemEntity::getId);
        if (code != null && !code.isBlank()) {
            // 単票再印刷（M2-9）：管理番号完全一致のみ。全角/小写容错=NFKC+大文字化。
            // 日付/会場条件は不問（古いラベルの張り替えは作成日を覚えている前提がない）。
            wrapper.eq(ItemEntity::getItemCode,
                    Normalizer.normalize(code.trim(), Normalizer.Form.NFKC).toUpperCase(Locale.ROOT));
        } else {
            if (createdFrom.isAfter(createdTo)) {
                throw new BizException(ErrorCode.VALIDATION, "作成日範囲の開始が終了より後になっています");
            }
            wrapper.ge(ItemEntity::getCreatedAt, LocalDateTime.of(createdFrom, LocalTime.MIN))
                    .lt(ItemEntity::getCreatedAt, LocalDateTime.of(createdTo.plusDays(1), LocalTime.MIN))
                    .eq(venueId != null, ItemEntity::getVenueId, venueId);
        }
        Page<ItemEntity> result = itemMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<Long> itemIds = result.getRecords().stream().map(ItemEntity::getId).toList();
        Map<Long, String> firstThumbs = firstThumbReader.byItemIds(itemIds);
        List<ItemSummaryResponse> rows = result.getRecords().stream()
                .map(item -> ItemSummaryResponse.from(item, firstThumbs.get(item.getId())))
                .toList();
        return new ItemListResponse(result.getTotal(), safePage, safeSize, rows);
    }

    /**
     * 导出流式遍历（M4-⑤，D-058 F）：筛选语义与 {@link #listForPrint} 完全一致
     * （同一数据源单一出处——两处查询漂移=导出与列表对不上）；差异仅取数方式——
     * keyset 分页（id 升序 LIMIT 批次）逐批回调，5 万行不整表进堆（docs/01 五节）。
     * 排除作废/软删件（导出=报告口径，非回收站）。
     */
    public void forEachItemForExport(LocalDate createdFrom, LocalDate createdTo,
            Long venueId, String code, java.util.function.Consumer<ItemEntity> consumer) {
        String normalizedCode = code != null && !code.isBlank()
                ? CodeNormalizer.normalize(code) : null;
        if (normalizedCode == null && createdFrom.isAfter(createdTo)) {
            throw new BizException(ErrorCode.VALIDATION, "作成日範囲の開始が終了より後になっています");
        }
        Long lastId = 0L;
        while (true) {
            LambdaQueryWrapper<ItemEntity> wrapper = new LambdaQueryWrapper<ItemEntity>()
                    .eq(ItemEntity::getVoided, 0)
                    .eq(ItemEntity::getDeleted, 0)
                    .gt(ItemEntity::getId, lastId)
                    .orderByAsc(ItemEntity::getId)
                    .last("LIMIT " + EXPORT_PAGE_SIZE);
            if (normalizedCode != null) {
                // 単票抽出（単票再印刷と同優先）：码条件优先于日期/会场条件
                wrapper.eq(ItemEntity::getItemCode, normalizedCode);
            } else {
                wrapper.ge(ItemEntity::getCreatedAt, LocalDateTime.of(createdFrom, LocalTime.MIN))
                        .lt(ItemEntity::getCreatedAt, LocalDateTime.of(createdTo.plusDays(1), LocalTime.MIN))
                        .eq(venueId != null, ItemEntity::getVenueId, venueId);
            }
            List<ItemEntity> page = itemMapper.selectList(wrapper);
            if (page.isEmpty()) {
                return;
            }
            page.forEach(consumer);
            lastId = page.get(page.size() - 1).getId();
            if (page.size() < EXPORT_PAGE_SIZE) {
                return;
            }
        }
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
