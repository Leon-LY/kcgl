package com.kcgl.module.inventory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kcgl.common.audit.AuditRecorder;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.image.FirstThumbReader;
import com.kcgl.module.inventory.dto.ArrivalRequest;
import com.kcgl.module.inventory.dto.ArrivalResponse;
import com.kcgl.module.inventory.dto.PendingArrivalResponse;
import com.kcgl.module.item.ItemEntity;
import com.kcgl.module.item.ItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 到货核对（M2-8a，docs/01 7.2 ARRIVAL 行）：在途清单 + 批量确认入库——
 * 承载「货直送仓库、标签还在办公室」路径：办公室录件（在途）→ 仓库到货点选确认。
 *
 * <p>批量语义：同一事务全成全败；混入非在途/作废/软删件→整批 409/404 回滚。
 * 幂等（docs/01 7.0）：每行独立 clientReqId，重放行读回原结果 200 出清（不重复入账）；
 * 乐观锁冲突重读重试一次，仍冲突→CONFLICT。
 */
@Service
public class ArrivalService {

    /** 单批上限（一次到货卡车的合理点选量级，防误选全量在途件长事务）。 */
    static final int MAX_BATCH = 100;
    private static final int MAX_PAGE_SIZE = 100;

    private final ItemMapper itemMapper;
    private final StockLedgerMapper ledgerMapper;
    private final FirstThumbReader firstThumbReader;
    private final AuditRecorder auditRecorder;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    public ArrivalService(ItemMapper itemMapper, StockLedgerMapper ledgerMapper,
            FirstThumbReader firstThumbReader, AuditRecorder auditRecorder,
            TransactionTemplate txTemplate, Clock clock) {
        this.itemMapper = itemMapper;
        this.ledgerMapper = ledgerMapper;
        this.firstThumbReader = firstThumbReader;
        this.auditRecorder = auditRecorder;
        this.txTemplate = txTemplate;
        this.clock = clock;
    }

    /** 在途清单：可选预计仓库筛选；排除已入库/作废/软删；新录入在前（id 倒序）。 */
    public PendingArrivalResponse pending(Integer warehouse, int page, int size) {
        if (warehouse != null && warehouse != 1 && warehouse != 2) {
            throw new BizException(ErrorCode.VALIDATION, "倉庫の指定が正しくありません");
        }
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<ItemEntity> result = itemMapper.selectPage(new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<ItemEntity>()
                        .eq(ItemEntity::getStockStatus, 0)
                        .eq(ItemEntity::getVoided, 0)
                        .eq(ItemEntity::getDeleted, 0)
                        .eq(warehouse != null, ItemEntity::getWarehouse, warehouse)
                        .orderByDesc(ItemEntity::getId));
        List<Long> itemIds = result.getRecords().stream().map(ItemEntity::getId).toList();
        Map<Long, String> thumbs = firstThumbReader.byItemIds(itemIds);
        List<PendingArrivalResponse.Row> rows = result.getRecords().stream()
                .map(item -> PendingArrivalResponse.Row.from(item, thumbs.get(item.getId())))
                .toList();
        return new PendingArrivalResponse(result.getTotal(), safePage, safeSize, rows);
    }

    /** 批量确认入库：请求形校验（空批/超量/键重复/未来入库日）先行，DB 写全在同一事务。 */
    public ArrivalResponse arrive(ArrivalRequest req, long operatorId, String operatorName) {
        List<ArrivalRequest.ArrivalLine> lines = req.items();
        if (lines == null || lines.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION, "入庫確認する商品が選択されていません");
        }
        if (lines.size() > MAX_BATCH) {
            throw new BizException(ErrorCode.VALIDATION,
                    "一度に入庫確認できるのは" + MAX_BATCH + "件までです");
        }
        Set<String> seenKeys = new HashSet<>();
        for (ArrivalRequest.ArrivalLine line : lines) {
            if (line.itemId() == null || line.clientReqId() == null || line.clientReqId().isBlank()) {
                throw new BizException(ErrorCode.VALIDATION,
                        "各商品のIDと冪等キーは必須です");
            }
            if (!seenKeys.add(line.clientReqId())) {
                throw new BizException(ErrorCode.VALIDATION,
                        "同一リクエスト内で重複した冪等キーがあります");
            }
        }
        LocalDate today = LocalDate.now(clock);
        if (req.warehouseInDate() != null && req.warehouseInDate().isAfter(today)) {
            throw new BizException(ErrorCode.VALIDATION, "入庫日に未来の日付は指定できません");
        }
        return txTemplate.execute(status ->
                arriveInTx(lines, req.warehouseInDate(), operatorId, operatorName));
    }

    // ------------------------------------------------------------------ 内部

    private ArrivalResponse arriveInTx(List<ArrivalRequest.ArrivalLine> lines, LocalDate reqInDate,
            long operatorId, String operatorName) {
        List<ArrivalResponse.ArrivedItem> arrived = new ArrayList<>(lines.size());
        for (ArrivalRequest.ArrivalLine line : lines) {
            ItemEntity replayed = findReplayedArrival(line);
            if (replayed != null) {
                arrived.add(ArrivalResponse.ArrivedItem.from(replayed));
                continue;
            }
            arrived.add(arriveOne(line, reqInDate, operatorId, operatorName));
        }
        return new ArrivalResponse(arrived.size(), List.copyOf(arrived));
    }

    /**
     * 单件到仓（事务内）：边表校验（非在途→409008 整批回滚）→ 乐观锁条件更新
     * （version 冲突重读重试一次，仍败→CONFLICT）→ ARRIVAL 流水 + 审计。
     */
    private ArrivalResponse.ArrivedItem arriveOne(ArrivalRequest.ArrivalLine line, LocalDate reqInDate,
            long operatorId, String operatorName) {
        Integer override = line.warehouse();
        if (override != null && override != 1 && override != 2) {
            throw new BizException(ErrorCode.VALIDATION, "倉庫の指定が正しくありません");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            ItemEntity item = requireArrivable(line.itemId());
            InventoryStateMachine.Outcome outcome = InventoryStateMachine.apply(
                    InventoryAction.ARRIVAL, item.getStockStatus(), item.getSaleStatus());
            int targetWarehouse = override != null ? override : item.getWarehouse();
            LocalDate inDate = resolveInDate(reqInDate, item);
            LocalDateTime now = LocalDateTime.now(clock);
            int rows = itemMapper.update(null, new LambdaUpdateWrapper<ItemEntity>()
                    .eq(ItemEntity::getId, item.getId())
                    .eq(ItemEntity::getVersion, item.getVersion())
                    .set(ItemEntity::getStockStatus, outcome.stockTo())
                    .set(ItemEntity::getSaleStatus, outcome.saleTo())
                    .set(ItemEntity::getWarehouse, targetWarehouse)
                    .set(line.shelfNo() != null, ItemEntity::getShelfNo, line.shelfNo())
                    .set(ItemEntity::getWarehouseInDate, inDate)
                    .set(ItemEntity::getUpdatedBy, operatorId)
                    .set(ItemEntity::getUpdatedAt, now)
                    .set(ItemEntity::getVersion, item.getVersion() + 1));
            if (rows > 0) {
                ledgerMapper.insert(buildArrivalLedger(line, item, targetWarehouse,
                        operatorId, operatorName, now));
                auditRecorder.record("ITEM_ARRIVAL", "item", item.getId(),
                        auditDetail(item, targetWarehouse, inDate, line));
                return new ArrivalResponse.ArrivedItem(item.getId(), item.getItemCode(),
                        outcome.stockTo(), targetWarehouse);
            }
            // version 冲突：重读后走同一套校验——并发方若已使该件不可到仓，自然 409008/404/409006
        }
        throw new BizException(ErrorCode.CONFLICT);
    }

    /** 幂等读回：同 clientReqId 的 ARRIVAL 流水已存在=重放，返回已入库原商品（不重复入账）。 */
    private ItemEntity findReplayedArrival(ArrivalRequest.ArrivalLine line) {
        StockLedgerEntity ledger = ledgerMapper.selectOne(new LambdaQueryWrapper<StockLedgerEntity>()
                .eq(StockLedgerEntity::getClientReqId, line.clientReqId())
                .eq(StockLedgerEntity::getTxnType, TxnType.ARRIVAL.id()));
        if (ledger == null) {
            return null;
        }
        if (!ledger.getItemId().equals(line.itemId())) {
            // 键被其他商品占用=前端缺陷，静默返回错数据比报错更危险
            throw new BizException(ErrorCode.VALIDATION,
                    "冪等キーが他の商品で使用されています");
        }
        ItemEntity item = itemMapper.selectById(ledger.getItemId());
        if (item == null) {
            throw new BizException(ErrorCode.INTERNAL, "入庫確認の再現に失敗しました");
        }
        return item;
    }

    /** 到仓前提：存在且未软删（回收站件按不存在）；未作废（作废=冻结禁一切迁移）。 */
    private ItemEntity requireArrivable(long itemId) {
        ItemEntity item = itemMapper.selectById(itemId);
        if (item == null || (item.getDeleted() != null && item.getDeleted() == 1)) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        if (item.getVoided() != null && item.getVoided() == 1) {
            throw new BizException(ErrorCode.ITEM_ALREADY_VOIDED);
        }
        return item;
    }

    /** 入库日取值：请求显式 > 录入预填（到仓不覆盖预填，docs/01 4.3 连续录入页）> 默认今天 JST。 */
    private LocalDate resolveInDate(LocalDate reqInDate, ItemEntity item) {
        if (reqInDate != null) {
            return reqInDate;
        }
        return item.getWarehouseInDate() != null ? item.getWarehouseInDate() : LocalDate.now(clock);
    }

    /** ARRIVAL 行：在途不占仓账（wh_from=NULL）→目标仓 +1；销售态不变不落列。 */
    private StockLedgerEntity buildArrivalLedger(ArrivalRequest.ArrivalLine line, ItemEntity item,
            int targetWarehouse, long operatorId, String operatorName, LocalDateTime now) {
        StockLedgerEntity ledger = new StockLedgerEntity();
        ledger.setClientReqId(line.clientReqId());
        ledger.setTxnType(TxnType.ARRIVAL.id());
        ledger.setItemId(item.getId());
        ledger.setItemCode(item.getItemCode());
        ledger.setStockFrom(0);
        ledger.setStockTo(1);
        ledger.setWhTo(targetWarehouse);
        ledger.setQtyChange(1);
        ledger.setOperatorId(operatorId);
        ledger.setOperatorName(operatorName);
        ledger.setCreatedAt(now);
        return ledger;
    }

    private Map<String, Object> auditDetail(ItemEntity item, int targetWarehouse, LocalDate inDate,
            ArrivalRequest.ArrivalLine line) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("itemCode", item.getItemCode());
        detail.put("whFrom", item.getWarehouse());
        detail.put("whTo", targetWarehouse);
        detail.put("warehouseInDate", inDate.toString());
        if (line.shelfNo() != null) {
            detail.put("shelfNo", line.shelfNo());
        }
        return detail;
    }
}
