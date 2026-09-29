package com.kcgl.module.inventory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kcgl.common.audit.AuditRecorder;
import com.kcgl.common.sse.SseHub;
import com.kcgl.common.sse.SyncEvent;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.inventory.dto.ActionResult;
import com.kcgl.module.inventory.dto.MarkCanceledRequest;
import com.kcgl.module.inventory.dto.MarkListedRequest;
import com.kcgl.module.inventory.dto.ReturnRequest;
import com.kcgl.module.inventory.dto.ScrapRequest;
import com.kcgl.module.inventory.dto.SellRequest;
import com.kcgl.module.inventory.dto.TransferRequest;
import com.kcgl.module.item.ItemEntity;
import com.kcgl.module.item.ItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 库存动作端点（M3-⑤，docs/01 7.2 边表展开）：卖出/报废/调拨/退货双向/手动上架标记。
 *
 * <p>统一骨架（与 ArrivalService 同构）：请求形校验 → 幂等读回（重放 200 原结果出清，
 * docs/01 7.0）→ requireActionable → 边表校验（非法 409008）→ 乐观锁条件更新 → 流水+审计
 * → 提交后 SSE 广播（仅新动作；重放不产生第二条流水，同样不扰全店重取）。
 * version 冲突的重试必须在事务边界之外（docs/01 7.1 C1 同纪律）：REPEATABLE READ 下
 * 事务内重读是陈旧快照——败者 update 因行锁等到胜者提交后 0 行返回，换新事务重读才能
 * 看见胜者的流水/新版本，按键读回 200 出清（并发同键双发绝不 409）。
 *
 * <p>流水入账规则（docs/01 5.3 对账不变量，与 M3-② 校验器解读严格一致）：
 * 每行 wh_to 记 +1 / wh_from 记 −1（NULL 不入账）——SELL/SCRAP 在库件记 (仓,−1)；
 * TRANSFER 双侧 (A,−1)(B,+1) qty=0；顾客退回记 (仓,+1)；退回拍卖场在库件 (仓,−1)、
 * 在途件不占仓账 qty=0；LIST_UP 实物未动 wh 全 NULL qty=0。
 */
@Service
public class InventoryActionService {

    /** 单值金额上限（日元，docs/01 五节：应用层统一上限）。 */
    static final long MAX_UNIT_PRICE = 99_999_999L;

    private final ItemMapper itemMapper;
    private final StockLedgerMapper ledgerMapper;
    private final AuditRecorder auditRecorder;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final SseHub sseHub;

    public InventoryActionService(ItemMapper itemMapper, StockLedgerMapper ledgerMapper,
            AuditRecorder auditRecorder, TransactionTemplate txTemplate, Clock clock, SseHub sseHub) {
        this.itemMapper = itemMapper;
        this.ledgerMapper = ledgerMapper;
        this.auditRecorder = auditRecorder;
        this.txTemplate = txTemplate;
        this.clock = clock;
        this.sseHub = sseHub;
    }

    /** 动作写入器：乐观锁条件更新+流水+审计；version 冲突返回 null 由外层重试。 */
    @FunctionalInterface
    interface ItemWriter {
        ActionResult write(ItemEntity item, InventoryStateMachine.Outcome outcome, LocalDateTime now);
    }

    /** 幂等骨架的执行结果：fresh=false 为重放（读回原结果，不再广播）。 */
    private record Executed(ActionResult result, boolean fresh) {
    }

    // ------------------------------------------------------------------ 卖出

    /** 卖出：在库→已出库，销售态→成交；soldPrice 选填（线下直卖 A10，CSV 成交价优先）。 */
    public ActionResult sell(SellRequest req, long operatorId, String operatorName) {
        if (req.soldPrice() != null && (req.soldPrice() < 1 || req.soldPrice() > MAX_UNIT_PRICE)) {
            throw new BizException(ErrorCode.VALIDATION,
                    "売却価格は1円以上" + MAX_UNIT_PRICE + "円以内で入力してください");
        }
        Executed done = act(InventoryAction.SELL, TxnType.SELL,
                req.itemId(), req.clientReqId(), operatorId, operatorName,
                (item, outcome, now) -> {
                    if (updateVersioned(item, outcome, operatorId, now,
                            w -> w.set(req.soldPrice() != null, ItemEntity::getSoldPrice, req.soldPrice())) == 0) {
                        return null;
                    }
                    StockLedgerEntity ledger = baseLedger(TxnType.SELL, req.clientReqId(), item,
                            operatorId, operatorName, now);
                    ledger.setStockFrom(item.getStockStatus());
                    ledger.setStockTo(outcome.stockTo());
                    ledger.setSaleFrom(item.getSaleStatus());
                    ledger.setSaleTo(outcome.saleTo());
                    ledger.setWhFrom(item.getWarehouse());
                    ledger.setQtyChange(-1);
                    ledgerMapper.insert(ledger);
                    Map<String, Object> detail = new HashMap<>();
                    detail.put("itemCode", item.getItemCode());
                    if (req.soldPrice() != null) {
                        detail.put("soldPrice", req.soldPrice());
                    }
                    auditRecorder.record("ITEM_SELL", "item", item.getId(), detail);
                    return resultOf(item, outcome, item.getWarehouse());
                });
        return broadcastFresh(done, operatorId);
    }

    // ------------------------------------------------------------------ 报废

    /** 报废：在库→已出库，任意销售态→取消；原因必填。 */
    public ActionResult scrap(ScrapRequest req, long operatorId, String operatorName) {
        Executed done = act(InventoryAction.SCRAP, TxnType.SCRAP,
                req.itemId(), req.clientReqId(), operatorId, operatorName,
                (item, outcome, now) -> {
                    if (updateVersioned(item, outcome, operatorId, now, w -> { }) == 0) {
                        return null;
                    }
                    StockLedgerEntity ledger = baseLedger(TxnType.SCRAP, req.clientReqId(), item,
                            operatorId, operatorName, now);
                    ledger.setStockFrom(item.getStockStatus());
                    ledger.setStockTo(outcome.stockTo());
                    ledger.setSaleFrom(item.getSaleStatus());
                    ledger.setSaleTo(outcome.saleTo());
                    ledger.setWhFrom(item.getWarehouse());
                    ledger.setQtyChange(-1);
                    ledger.setReason(req.reason());
                    ledgerMapper.insert(ledger);
                    auditRecorder.record("ITEM_SCRAP", "item", item.getId(), Map.of(
                            "itemCode", item.getItemCode(),
                            "reason", req.reason()));
                    return resultOf(item, outcome, item.getWarehouse());
                });
        return broadcastFresh(done, operatorId);
    }

    // ------------------------------------------------------------------ 调拨

    /** 调拨：在库→在库（stock 不变），仓 A→B 由 ledger 双向入账表达。 */
    public ActionResult transfer(TransferRequest req, long operatorId, String operatorName) {
        if (req.toWarehouse() != 1 && req.toWarehouse() != 2) {
            throw new BizException(ErrorCode.VALIDATION, "倉庫の指定が正しくありません");
        }
        Executed done = act(InventoryAction.TRANSFER, TxnType.TRANSFER,
                req.itemId(), req.clientReqId(), operatorId, operatorName,
                (item, outcome, now) -> {
                    if (req.toWarehouse().equals(item.getWarehouse())) {
                        throw new BizException(ErrorCode.VALIDATION, "既にその倉庫にあります");
                    }
                    if (updateVersioned(item, outcome, operatorId, now,
                            w -> w.set(ItemEntity::getWarehouse, req.toWarehouse())) == 0) {
                        return null;
                    }
                    StockLedgerEntity ledger = baseLedger(TxnType.TRANSFER, req.clientReqId(), item,
                            operatorId, operatorName, now);
                    ledger.setStockFrom(item.getStockStatus());
                    ledger.setStockTo(outcome.stockTo());
                    ledger.setWhFrom(item.getWarehouse());
                    ledger.setWhTo(req.toWarehouse());
                    ledger.setQtyChange(0);
                    ledgerMapper.insert(ledger);
                    auditRecorder.record("ITEM_TRANSFER", "item", item.getId(), Map.of(
                            "itemCode", item.getItemCode(),
                            "whFrom", item.getWarehouse(),
                            "whTo", req.toWarehouse()));
                    return resultOf(item, outcome, req.toWarehouse());
                });
        return broadcastFresh(done, operatorId);
    }

    // ------------------------------------------------------------------ 退货

    /** 退货双向入口：direction 1=顾客退回 2=退回拍卖场。 */
    public ActionResult returnItem(ReturnRequest req, long operatorId, String operatorName) {
        if (req.direction() != 1 && req.direction() != 2) {
            throw new BizException(ErrorCode.VALIDATION, "返品の種別が正しくありません");
        }
        return req.direction() == 1
                ? returnFromCustomer(req, operatorId, operatorName)
                : returnToVenue(req, operatorId, operatorName);
    }

    /** 顾客退回：已出库→在库（须成交态，边表把守），成交→取消；回原仓记 (仓,+1)。 */
    private ActionResult returnFromCustomer(ReturnRequest req, long operatorId, String operatorName) {
        Executed done = act(InventoryAction.RETURN_CUSTOMER, TxnType.RETURN,
                req.itemId(), req.clientReqId(), operatorId, operatorName,
                (item, outcome, now) -> {
                    if (updateVersioned(item, outcome, operatorId, now, w -> { }) == 0) {
                        return null;
                    }
                    StockLedgerEntity ledger = baseLedger(TxnType.RETURN, req.clientReqId(), item,
                            operatorId, operatorName, now);
                    ledger.setStockFrom(item.getStockStatus());
                    ledger.setStockTo(outcome.stockTo());
                    ledger.setSaleFrom(item.getSaleStatus());
                    ledger.setSaleTo(outcome.saleTo());
                    ledger.setWhTo(item.getWarehouse());
                    ledger.setQtyChange(1);
                    ledger.setReturnDirection(1);
                    if (req.note() != null) {
                        ledger.setReason(req.note());
                    }
                    ledgerMapper.insert(ledger);
                    auditRecorder.record("ITEM_RETURN_CUSTOMER", "item", item.getId(),
                            returnDetail(item, req.note()));
                    return resultOf(item, outcome, item.getWarehouse());
                });
        return broadcastFresh(done, operatorId);
    }

    /** 退回拍卖场：在途/在库→已出库，销售态→取消；在库件记 (仓,−1)，在途件不占仓账。 */
    private ActionResult returnToVenue(ReturnRequest req, long operatorId, String operatorName) {
        Executed done = act(InventoryAction.RETURN_VENUE, TxnType.RETURN,
                req.itemId(), req.clientReqId(), operatorId, operatorName,
                (item, outcome, now) -> {
                    if (updateVersioned(item, outcome, operatorId, now, w -> { }) == 0) {
                        return null;
                    }
                    StockLedgerEntity ledger = baseLedger(TxnType.RETURN, req.clientReqId(), item,
                            operatorId, operatorName, now);
                    ledger.setStockFrom(item.getStockStatus());
                    ledger.setStockTo(outcome.stockTo());
                    ledger.setSaleFrom(item.getSaleStatus());
                    ledger.setSaleTo(outcome.saleTo());
                    boolean inStock = item.getStockStatus() == 1;
                    ledger.setWhFrom(inStock ? item.getWarehouse() : null);
                    ledger.setQtyChange(inStock ? -1 : 0);
                    ledger.setReturnDirection(2);
                    if (req.note() != null) {
                        ledger.setReason(req.note());
                    }
                    ledgerMapper.insert(ledger);
                    auditRecorder.record("ITEM_RETURN_VENUE", "item", item.getId(),
                            returnDetail(item, req.note()));
                    return resultOf(item, outcome, item.getWarehouse());
                });
        return broadcastFresh(done, operatorId);
    }

    // ------------------------------------------------------------------ 上架标记

    /** 手动上架标记（LIST_UP）：在库且未上架/已取消→在售；实物未动（wh 全 NULL qty=0）。 */
    public ActionResult markListed(MarkListedRequest req, long operatorId, String operatorName) {
        Executed done = act(InventoryAction.LIST_UP, TxnType.LIST_UP,
                req.itemId(), req.clientReqId(), operatorId, operatorName,
                (item, outcome, now) -> {
                    if (updateVersioned(item, outcome, operatorId, now, w -> { }) == 0) {
                        return null;
                    }
                    StockLedgerEntity ledger = baseLedger(TxnType.LIST_UP, req.clientReqId(), item,
                            operatorId, operatorName, now);
                    ledger.setStockFrom(item.getStockStatus());
                    ledger.setStockTo(outcome.stockTo());
                    ledger.setSaleFrom(item.getSaleStatus());
                    ledger.setSaleTo(outcome.saleTo());
                    ledger.setQtyChange(0);
                    ledgerMapper.insert(ledger);
                    auditRecorder.record("ITEM_LIST_UP", "item", item.getId(),
                            Map.of("itemCode", item.getItemCode()));
                    return resultOf(item, outcome, item.getWarehouse());
                });
        return broadcastFresh(done, operatorId);
    }

    // ------------------------------------------------------------------ 取消标记

    /**
     * 手动取消标记（CANCEL_MARK，D-069）：在库且在售→取消（流拍/出品取消的登记口）。
     * 受注表无取消信息，手动标记是 CANCEL_MARK 唯一来源；实物未动（wh 全 NULL qty=0）。
     */
    public ActionResult markCanceled(MarkCanceledRequest req, long operatorId, String operatorName) {
        Executed done = act(InventoryAction.CANCEL_MARK, TxnType.CANCEL_MARK,
                req.itemId(), req.clientReqId(), operatorId, operatorName,
                (item, outcome, now) -> {
                    if (updateVersioned(item, outcome, operatorId, now, w -> { }) == 0) {
                        return null;
                    }
                    StockLedgerEntity ledger = baseLedger(TxnType.CANCEL_MARK, req.clientReqId(), item,
                            operatorId, operatorName, now);
                    ledger.setStockFrom(item.getStockStatus());
                    ledger.setStockTo(outcome.stockTo());
                    ledger.setSaleFrom(item.getSaleStatus());
                    ledger.setSaleTo(outcome.saleTo());
                    ledger.setQtyChange(0);
                    ledgerMapper.insert(ledger);
                    auditRecorder.record("ITEM_CANCEL_MARK", "item", item.getId(),
                            Map.of("itemCode", item.getItemCode()));
                    return resultOf(item, outcome, item.getWarehouse());
                });
        return broadcastFresh(done, operatorId);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 幂等骨架（重试在事务边界之外，docs/01 7.1 C1 同纪律）：每轮独立事务=独立快照。
     * 并发同键双发（连点/双端重试）时序：败者 attempt-0 的 update 因行锁等到胜者提交后
     * 0 行返回 → attempt-1 新事务里按键读回胜者流水 → 200 出清（7.0：重放绝不 409）。
     * 终局再复核一次——最后一轮 update 返回时胜者事务必已提交，事务外 SELECT 看得见。
     * 业务错误（404/409008/409006/400）在单轮事务内直接抛出，不触发重试。
     */
    private Executed act(InventoryAction action, TxnType txnType, long itemId, String clientReqId,
            long operatorId, String operatorName, ItemWriter writer) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Executed done = txTemplate.execute(status -> actOnce(
                    action, txnType, itemId, clientReqId, operatorId, operatorName, writer));
            if (done != null) {
                return done;
            }
        }
        ItemEntity lateReplay = findReplayed(clientReqId, txnType, itemId);
        if (lateReplay != null) {
            return new Executed(resultOf(lateReplay, null, null), false);
        }
        throw new BizException(ErrorCode.CONFLICT);
    }

    /**
     * 单轮（事务内）：同 clientReqId 流水已存在=网络重放→读回商品现态 200 出清；
     * 否则「读件→边表校验→写入器」，version 冲突由写入器返回 null——本事务无写入、
     * 空提交，外层换新事务重试（重读后走同一套校验——并发方已使动作非法则自然
     * 409008/404/409006）。
     */
    private Executed actOnce(InventoryAction action, TxnType txnType, long itemId, String clientReqId,
            long operatorId, String operatorName, ItemWriter writer) {
        ItemEntity replayed = findReplayed(clientReqId, txnType, itemId);
        if (replayed != null) {
            return new Executed(resultOf(replayed, null, null), false);
        }
        ItemEntity item = requireActionable(itemId);
        InventoryStateMachine.Outcome outcome = InventoryStateMachine.apply(
                action, item.getStockStatus(), item.getSaleStatus());
        ActionResult result = writer.write(item, outcome, LocalDateTime.now(clock));
        return result != null ? new Executed(result, true) : null;
    }

    /** 幂等读回：按全局唯一键查（uk_client_req）；类型/商品不符=键被挪用→400 防脏读。 */
    private ItemEntity findReplayed(String clientReqId, TxnType expectedType, long itemId) {
        StockLedgerEntity ledger = ledgerMapper.selectOne(new LambdaQueryWrapper<StockLedgerEntity>()
                .eq(StockLedgerEntity::getClientReqId, clientReqId));
        if (ledger == null) {
            return null;
        }
        if (ledger.getTxnType() != expectedType.id()) {
            throw new BizException(ErrorCode.VALIDATION, "冪等キーが他の操作で使用されています");
        }
        if (!ledger.getItemId().equals(itemId)) {
            throw new BizException(ErrorCode.VALIDATION, "冪等キーが他の商品で使用されています");
        }
        ItemEntity item = itemMapper.selectById(ledger.getItemId());
        if (item == null) {
            throw new BizException(ErrorCode.INTERNAL, "在庫操作の再現に失敗しました");
        }
        return item;
    }

    /** 动作前提：存在且未软删（回收站件按不存在）；未作废（作废=冻结禁一切迁移）。 */
    private ItemEntity requireActionable(long itemId) {
        ItemEntity item = itemMapper.selectById(itemId);
        if (item == null || (item.getDeleted() != null && item.getDeleted() == 1)) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        if (item.getVoided() != null && item.getVoided() == 1) {
            throw new BizException(ErrorCode.ITEM_ALREADY_VOIDED);
        }
        return item;
    }

    /** 乐观锁条件更新（stock/sale 恒写 + 动作专列回调）；返回影响行数。 */
    private int updateVersioned(ItemEntity item, InventoryStateMachine.Outcome outcome,
            long operatorId, LocalDateTime now, Consumer<LambdaUpdateWrapper<ItemEntity>> extra) {
        LambdaUpdateWrapper<ItemEntity> wrapper = new LambdaUpdateWrapper<ItemEntity>()
                .eq(ItemEntity::getId, item.getId())
                .eq(ItemEntity::getVersion, item.getVersion())
                .set(ItemEntity::getStockStatus, outcome.stockTo())
                .set(ItemEntity::getSaleStatus, outcome.saleTo())
                .set(ItemEntity::getUpdatedBy, operatorId)
                .set(ItemEntity::getUpdatedAt, now)
                .set(ItemEntity::getVersion, item.getVersion() + 1);
        extra.accept(wrapper);
        return itemMapper.update(null, wrapper);
    }

    private ActionResult resultOf(ItemEntity item, InventoryStateMachine.Outcome outcome, Integer warehouse) {
        int stock = outcome != null ? outcome.stockTo() : item.getStockStatus();
        int sale = outcome != null ? outcome.saleTo() : item.getSaleStatus();
        return new ActionResult(item.getId(), item.getItemCode(), stock, sale,
                warehouse != null ? warehouse : item.getWarehouse());
    }

    /** 提交后广播（仅新动作）：entity=管理号，客户端按 INVENTORY 域整体失效重取。 */
    private ActionResult broadcastFresh(Executed done, long operatorId) {
        if (done.fresh()) {
            sseHub.broadcast(SyncEvent.TYPE_INVENTORY, done.result().itemCode(), operatorId);
        }
        return done.result();
    }

    private StockLedgerEntity baseLedger(TxnType txnType, String clientReqId, ItemEntity item,
            long operatorId, String operatorName, LocalDateTime now) {
        StockLedgerEntity ledger = new StockLedgerEntity();
        ledger.setClientReqId(clientReqId);
        ledger.setTxnType(txnType.id());
        ledger.setItemId(item.getId());
        ledger.setItemCode(item.getItemCode());
        ledger.setOperatorId(operatorId);
        ledger.setOperatorName(operatorName);
        ledger.setCreatedAt(now);
        return ledger;
    }

    private Map<String, Object> returnDetail(ItemEntity item, String note) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("itemCode", item.getItemCode());
        if (note != null) {
            detail.put("note", note);
        }
        return detail;
    }
}
