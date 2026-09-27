package com.kcgl.module.inventory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kcgl.common.audit.AuditRecorder;
import com.kcgl.common.sse.SseHub;
import com.kcgl.common.sse.SyncEvent;
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
 * 幂等（docs/01 7.0）：每行独立 clientReqId，重放行读回原结果 200 出清（不重复入账）。
 * version 冲突的重试在事务边界之外（docs/01 7.1 C1 同纪律）：整批回滚后换新事务重试
 * ——REPEATABLE READ 下事务内重读是陈旧快照，并发同键双发时败者必须在新事务里才能
 * 看见胜者流水，读回 200 出清（绝不 409）；两轮均冲突且非重放→CONFLICT。
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
    private final SseHub sseHub;

    public ArrivalService(ItemMapper itemMapper, StockLedgerMapper ledgerMapper,
            FirstThumbReader firstThumbReader, AuditRecorder auditRecorder,
            TransactionTemplate txTemplate, Clock clock, SseHub sseHub) {
        this.itemMapper = itemMapper;
        this.ledgerMapper = ledgerMapper;
        this.firstThumbReader = firstThumbReader;
        this.auditRecorder = auditRecorder;
        this.txTemplate = txTemplate;
        this.clock = clock;
        this.sseHub = sseHub;
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
        BatchOutcome outcome = null;
        for (int attempt = 0; attempt < 2 && outcome == null; attempt++) {
            outcome = arriveBatch(lines, req.warehouseInDate(), operatorId, operatorName);
        }
        if (outcome == null) {
            // 终局复核（事务外=最新已提交快照）：整批逐行读回——全部已入账=并发重放 200 出清
            outcome = replayedOutcome(lines);
        }
        if (outcome.fresh()) {
            // 批次级单次广播（entity=null，批量语义）：客户端按 INVENTORY 域整体失效重取
            sseHub.broadcast(SyncEvent.TYPE_INVENTORY, null, operatorId);
        }
        return outcome.response();
    }

    // ------------------------------------------------------------------ 内部

    /** 批量执行结果：fresh=本批含新入账行（纯重放批不广播）。 */
    private record BatchOutcome(ArrivalResponse response, boolean fresh) {
    }

    /** 整批执行（单事务）；任一行 version 冲突→已写行随整批回滚，返回 null 由外层换新事务重试。 */
    private BatchOutcome arriveBatch(List<ArrivalRequest.ArrivalLine> lines, LocalDate reqInDate,
            long operatorId, String operatorName) {
        return txTemplate.execute(status -> {
            BatchOutcome outcome = arriveInTx(lines, reqInDate, operatorId, operatorName);
            if (outcome == null) {
                status.setRollbackOnly();
            }
            return outcome;
        });
    }

    private BatchOutcome arriveInTx(List<ArrivalRequest.ArrivalLine> lines, LocalDate reqInDate,
            long operatorId, String operatorName) {
        List<ArrivalResponse.ArrivedItem> arrived = new ArrayList<>(lines.size());
        boolean fresh = false;
        for (ArrivalRequest.ArrivalLine line : lines) {
            ItemEntity replayed = findReplayedArrival(line);
            if (replayed != null) {
                arrived.add(ArrivalResponse.ArrivedItem.from(replayed));
                continue;
            }
            ArrivalResponse.ArrivedItem result = arriveOne(line, reqInDate, operatorId, operatorName);
            if (result == null) {
                return null; // version 冲突：整批回滚换新事务重试（新快照才能见胜者流水）
            }
            arrived.add(result);
            fresh = true;
        }
        return new BatchOutcome(new ArrivalResponse(arrived.size(), List.copyOf(arrived)), fresh);
    }

    /**
     * 终局复核：两轮整批重试均冲突后逐行按键读回（无事务=最新已提交快照）；
     * 任一行无对应流水=真冲突 CONFLICT（防把半批重放误报成功）。
     */
    private BatchOutcome replayedOutcome(List<ArrivalRequest.ArrivalLine> lines) {
        List<ArrivalResponse.ArrivedItem> replayed = new ArrayList<>(lines.size());
        for (ArrivalRequest.ArrivalLine line : lines) {
            ItemEntity item = findReplayedArrival(line);
            if (item == null) {
                throw new BizException(ErrorCode.CONFLICT);
            }
            replayed.add(ArrivalResponse.ArrivedItem.from(item));
        }
        return new BatchOutcome(new ArrivalResponse(replayed.size(), List.copyOf(replayed)), false);
    }

    /**
     * 单件到仓（事务内单轮，重放读回在 arriveInTx 行首）：前提守卫 → 边表校验
     * （非在途→409008 整批回滚）→ 乐观锁条件更新 → ARRIVAL 流水 + 审计；
     * version 冲突返回 null（并发方若已使该件不可到仓，重试轮自然 409008/404/409006）。
     */
    private ArrivalResponse.ArrivedItem arriveOne(ArrivalRequest.ArrivalLine line, LocalDate reqInDate,
            long operatorId, String operatorName) {
        Integer override = line.warehouse();
        if (override != null && override != 1 && override != 2) {
            throw new BizException(ErrorCode.VALIDATION, "倉庫の指定が正しくありません");
        }
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
        return null;
    }

    /**
     * 幂等读回：按全局唯一键查（uk_client_req）；同键 ARRIVAL 流水=重放返回已入库原商品。
     * 类型不符=键被其他动作挪用（前端缺陷）——静默放行必然撞唯一键 500，显式 400。
     */
    private ItemEntity findReplayedArrival(ArrivalRequest.ArrivalLine line) {
        StockLedgerEntity ledger = ledgerMapper.selectOne(new LambdaQueryWrapper<StockLedgerEntity>()
                .eq(StockLedgerEntity::getClientReqId, line.clientReqId()));
        if (ledger == null) {
            return null;
        }
        if (ledger.getTxnType() != TxnType.ARRIVAL.id()) {
            throw new BizException(ErrorCode.VALIDATION,
                    "冪等キーが他の操作で使用されています");
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
