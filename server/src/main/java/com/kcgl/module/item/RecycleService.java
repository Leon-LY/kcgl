package com.kcgl.module.item;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kcgl.common.audit.AuditRecorder;
import com.kcgl.common.sse.SseHub;
import com.kcgl.common.sse.SyncEvent;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.dict.VenueEntity;
import com.kcgl.module.dict.VenueMapper;
import com.kcgl.module.image.FirstThumbReader;
import com.kcgl.module.inventory.StockLedgerEntity;
import com.kcgl.module.inventory.StockLedgerMapper;
import com.kcgl.module.inventory.TxnType;
import com.kcgl.module.item.dto.RecycleActionRequest;
import com.kcgl.module.item.dto.RecycleBatchRequest;
import com.kcgl.module.item.dto.RecycleBatchResponse;
import com.kcgl.module.item.dto.RecycleBinResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 回收站（D-064，管理员专用）。
 *
 * 软删=治理终态出口：作废件亦可入站（voided/deleted 两治理轴正交）。
 * 幂等（D-045 C）：findReplayed 按 clientReqId 查流水——不预过滤类型，
 * 类型不符=键被他操作占用（400），item 不符=键被他商品占用（400），
 * 命中本操作=读回原商品 200（不广播不补流水，与 VOID 语义同构）。
 *
 * 账实不变量：在库未作废件记 ∓1/+1；在途/已出库/已作废记 0
 * （已作废在库件 VOID 已记过 −1，再记即双重扣减破坏 Σledger≡COUNT）。
 * 恢复保序回软删前 stock_status，作废标志不动（作废轴独立于删除轴）。
 * version 不递增（回收站动作不参与编辑乐观锁链，避免管理员操作踢掉编辑中用户），
 * 但条件更新**读入 version 作陈旧快照守卫**：REPEATABLE READ 下事务内读到的是快照，
 * 若期间他事务（售出/编辑/作废）已提交改了 stock_status/voided，本事务条件更新因 version 不符返回
 * 0 行——换新事务重读重试，保证 buildLedger 记账所依的态与库中现态一致（否则并发售出会据旧态记出幻影 −1，
 * 破 Σledger≡COUNT；见 docs/04 D-109）。守卫只读 version，不回写，故不影响编辑方。
 */
@Service
public class RecycleService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ItemMapper itemMapper;
    private final StockLedgerMapper ledgerMapper;
    private final VenueMapper venueMapper;
    private final FirstThumbReader firstThumbReader;
    private final AuditRecorder auditRecorder;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final SseHub sseHub;

    public RecycleService(ItemMapper itemMapper, StockLedgerMapper ledgerMapper,
            VenueMapper venueMapper, FirstThumbReader firstThumbReader,
            AuditRecorder auditRecorder, TransactionTemplate txTemplate, Clock clock, SseHub sseHub) {
        this.itemMapper = itemMapper;
        this.ledgerMapper = ledgerMapper;
        this.venueMapper = venueMapper;
        this.firstThumbReader = firstThumbReader;
        this.auditRecorder = auditRecorder;
        this.txTemplate = txTemplate;
        this.clock = clock;
        this.sseHub = sseHub;
    }

    public ItemEntity delete(long itemId, RecycleActionRequest req,
            long operatorId, String operatorName) {
        ItemEntity replayed = findReplayed(req.clientReqId(), TxnType.RECYCLE_DELETE, itemId);
        if (replayed != null) {
            return replayed;
        }
        ItemEntity updated = reconcile(req, itemId, TxnType.RECYCLE_DELETE, true, operatorId, operatorName);
        sseHub.broadcast(SyncEvent.TYPE_ITEM, updated.getItemCode(), operatorId);
        return updated;
    }

    public ItemEntity restore(long itemId, RecycleActionRequest req,
            long operatorId, String operatorName) {
        ItemEntity replayed = findReplayed(req.clientReqId(), TxnType.RECYCLE_RESTORE, itemId);
        if (replayed != null) {
            return replayed;
        }
        ItemEntity updated = reconcile(req, itemId, TxnType.RECYCLE_RESTORE, false, operatorId, operatorName);
        sseHub.broadcast(SyncEvent.TYPE_ITEM, updated.getItemCode(), operatorId);
        return updated;
    }

    public RecycleBatchResponse deleteBatch(RecycleBatchRequest req, long operatorId, String operatorName) {
        return runBatch(req, true, operatorId, operatorName);
    }

    public RecycleBatchResponse restoreBatch(RecycleBatchRequest req, long operatorId, String operatorName) {
        return runBatch(req, false, operatorId, operatorName);
    }

    /**
     * 逐件走单件端点，各自成事务（{@link #reconcile} 每次 attempt 自开事务）。
     *
     * <p>只把 {@link BizException} 计为「该件失败」：已删（409014）/未删（409015）/不存在（404001）/
     * 版本冲突（409000）/键被占用（400001）都是**要逐件报告的业务结果**，不是批量的失败。
     * 其余异常（DB 故障、连接中断等）照常上抛——把它们混进 failures 会把「基础设施坏了」
     * 伪装成「这几件本来就不该删」，用户按失败清单重试也永远失败。
     *
     * <p>每件成功后 {@link #delete}/{@link #restore} 内部已各自广播 SSE，此处不重复广播：
     * 一件商品一条失效事件，与其他位点同粒度。
     */
    private RecycleBatchResponse runBatch(RecycleBatchRequest req, boolean deleting,
            long operatorId, String operatorName) {
        int succeeded = 0;
        List<RecycleBatchResponse.Failure> failures = new ArrayList<>();
        for (RecycleBatchRequest.Entry entry : req.items()) {
            RecycleActionRequest one = new RecycleActionRequest(entry.clientReqId(), req.reason());
            try {
                if (deleting) {
                    delete(entry.id(), one, operatorId, operatorName);
                } else {
                    restore(entry.id(), one, operatorId, operatorName);
                }
                succeeded++;
            } catch (BizException e) {
                failures.add(new RecycleBatchResponse.Failure(entry.id(), e.errorCode().code()));
            }
        }
        return new RecycleBatchResponse(succeeded, failures);
    }

    /**
     * 在事务边界之外重试：事务内版返回 null（version 守卫不符=读到陈旧快照）即空提交，
     * 换新事务重读——REPEATABLE READ 下只有新事务才看得见他事务已提交的现态
     * （与 InventoryActionService/ArrivalService 同纪律，docs/01 7.1 C1）。
     */
    private ItemEntity reconcile(RecycleActionRequest req, long itemId, TxnType type,
            boolean deleting, long operatorId, String operatorName) {
        for (int attempt = 0; attempt < 2; attempt++) {
            ItemEntity updated = txTemplate.execute(
                    status -> recycleOnce(req, itemId, type, deleting, operatorId, operatorName));
            if (updated != null) {
                return updated;
            }
        }
        throw new BizException(ErrorCode.CONFLICT);
    }

    /**
     * 单轮（事务内）：读件 → 态校验 → 版本守卫条件更新 → 流水 + 审计。
     * 更新因 version/态不符影响 0 行 → 返回 null（本事务尚无写入，空提交，外层重试）。
     */
    private ItemEntity recycleOnce(RecycleActionRequest req, long itemId, TxnType type,
            boolean deleting, long operatorId, String operatorName) {
        ItemEntity item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        boolean alreadyDeleted = item.getDeleted() != null && item.getDeleted() == 1;
        if (deleting && alreadyDeleted) {
            throw new BizException(ErrorCode.ITEM_ALREADY_DELETED);
        }
        if (!deleting && !alreadyDeleted) {
            throw new BizException(ErrorCode.ITEM_NOT_DELETED);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int rows = itemMapper.update(null, new LambdaUpdateWrapper<ItemEntity>()
                .eq(ItemEntity::getId, itemId)
                .eq(ItemEntity::getDeleted, deleting ? 0 : 1)
                .eq(ItemEntity::getVersion, item.getVersion())
                .set(ItemEntity::getDeleted, deleting ? 1 : 0)
                .set(ItemEntity::getDeletedBy, deleting ? operatorId : null)
                .set(ItemEntity::getDeletedAt, deleting ? now : null)
                .set(ItemEntity::getUpdatedBy, operatorId)
                .set(ItemEntity::getUpdatedAt, now));
        if (rows == 0) {
            return null; // 陈旧快照（他事务已提交）或态已变 → 换新事务重读重试
        }
        ledgerMapper.insert(buildLedger(item, type, req.clientReqId(),
                deleting ? req.reason() : null, operatorId, operatorName, now));
        Map<String, Object> detail = new HashMap<>();
        detail.put("itemCode", item.getItemCode());
        if (deleting) {
            detail.put("reason", req.reason());
        }
        detail.put("stockStatus", item.getStockStatus());
        auditRecorder.record(deleting ? "RECYCLE_DELETE" : "RECYCLE_RESTORE", "item", itemId, detail);
        return itemMapper.selectById(itemId);
    }

    public RecycleBinResponse bin(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<ItemEntity> result = itemMapper.selectPage(Page.of(safePage, safeSize),
                new LambdaQueryWrapper<ItemEntity>()
                        .eq(ItemEntity::getDeleted, 1)
                        .orderByDesc(ItemEntity::getDeletedAt)
                        .orderByDesc(ItemEntity::getId));
        List<ItemEntity> items = result.getRecords();
        Map<Long, String> thumbs = firstThumbReader.byItemIds(
                items.stream().map(ItemEntity::getId).toList());
        Map<Long, String> venueNames = venueNames(items);
        Map<Long, String> reasons = deleteReasons(items.stream().map(ItemEntity::getId).toList());

        List<RecycleBinResponse.Row> rows = items.stream()
                .map(item -> new RecycleBinResponse.Row(
                        item.getId(),
                        item.getItemCode(),
                        thumbs.get(item.getId()),
                        item.getItemName(),
                        venueNames.get(item.getVenueId()),
                        item.getWarehouse(),
                        item.getStockStatus(),
                        item.getSaleStatus(),
                        item.getVoided() != null && item.getVoided() == 1,
                        item.getDeletedAt(),
                        reasons.get(item.getId())))
                .toList();
        return new RecycleBinResponse(result.getTotal(), safePage, safeSize, rows);
    }

    private StockLedgerEntity buildLedger(ItemEntity item, TxnType type, String clientReqId,
            String reason, long operatorId, String operatorName, LocalDateTime now) {
        boolean inStock = Objects.equals(item.getStockStatus(), 1)
                && !(item.getVoided() != null && item.getVoided() == 1);
        StockLedgerEntity ledger = new StockLedgerEntity();
        ledger.setClientReqId(clientReqId);
        ledger.setTxnType(type.id());
        ledger.setItemId(item.getId());
        ledger.setItemCode(item.getItemCode());
        ledger.setReason(reason);
        ledger.setOperatorId(operatorId);
        ledger.setOperatorName(operatorName);
        ledger.setCreatedAt(now);
        if (type == TxnType.RECYCLE_DELETE) {
            ledger.setStockFrom(item.getStockStatus());
            ledger.setWhFrom(inStock ? item.getWarehouse() : null);
            ledger.setQtyChange(inStock ? -1 : 0);
        } else {
            ledger.setStockTo(item.getStockStatus());
            ledger.setWhTo(inStock ? item.getWarehouse() : null);
            ledger.setQtyChange(inStock ? 1 : 0);
        }
        return ledger;
    }

    /**
     * 幂等读回（D-045 C）：按 clientReqId 全表查流水（uk 至多一行），
     * 不按类型预过滤——跨类型/跨商品键占用必须显式 400 而非静默插入撞 uk。
     */
    private ItemEntity findReplayed(String clientReqId, TxnType expected, long itemId) {
        if (clientReqId == null || clientReqId.isBlank()) {
            return null;
        }
        StockLedgerEntity ledger = ledgerMapper.selectOne(new LambdaQueryWrapper<StockLedgerEntity>()
                .eq(StockLedgerEntity::getClientReqId, clientReqId));
        if (ledger == null) {
            return null;
        }
        if (ledger.getTxnType() == null || ledger.getTxnType() != expected.id()) {
            throw new BizException(ErrorCode.VALIDATION, "このキーは他の操作で使用されています");
        }
        if (ledger.getItemId() == null || ledger.getItemId() != itemId) {
            throw new BizException(ErrorCode.VALIDATION, "このキーは他の商品で使用されています");
        }
        ItemEntity item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ErrorCode.INTERNAL, "回収操作の読み戻しに失敗しました");
        }
        return item;
    }

    /** 最近一次软删流水的原因（历史多次软删取最新——orderByAsc id 后 put 覆盖即最新）。 */
    private Map<Long, String> deleteReasons(List<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        List<StockLedgerEntity> ledgers = ledgerMapper.selectList(
                new LambdaQueryWrapper<StockLedgerEntity>()
                        .in(StockLedgerEntity::getItemId, itemIds)
                        .eq(StockLedgerEntity::getTxnType, TxnType.RECYCLE_DELETE.id())
                        .orderByAsc(StockLedgerEntity::getId));
        Map<Long, String> reasons = new HashMap<>();
        for (StockLedgerEntity ledger : ledgers) {
            reasons.put(ledger.getItemId(), ledger.getReason());
        }
        return reasons;
    }

    private Map<Long, String> venueNames(List<ItemEntity> items) {
        List<Long> venueIds = items.stream()
                .map(ItemEntity::getVenueId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (venueIds.isEmpty()) {
            return Map.of();
        }
        return venueMapper.selectBatchIds(venueIds).stream()
                .collect(Collectors.toMap(VenueEntity::getId, VenueEntity::getName));
    }
}
