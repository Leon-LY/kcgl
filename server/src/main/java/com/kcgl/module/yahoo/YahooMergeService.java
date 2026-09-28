package com.kcgl.module.yahoo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kcgl.common.audit.AuditRecorder;
import com.kcgl.common.web.BizException;
import com.kcgl.module.inventory.InventoryAction;
import com.kcgl.module.inventory.InventoryStateMachine;
import com.kcgl.module.inventory.StockLedgerEntity;
import com.kcgl.module.inventory.StockLedgerMapper;
import com.kcgl.module.inventory.TxnType;
import com.kcgl.module.item.ItemEntity;
import com.kcgl.module.item.ItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 单行合并（docs/01 7.4）：listing 行级 UPSERT + 商品侧标记，一行一事务（坏行不连坐）。
 *
 * <p>listing：uk_auction 定位；状态单调 clamp（成交禁回退）、价格/时刻按事件时间
 * recency（同刻=后批胜）；last_seen_batch_id 恒更（对账「7 天内同步过降灰」的判据）。
 *
 * <p>商品侧标记（docs/01 7.2 边表复用，绝不改 stock_status）：仅当期望销售态与现值
 * 不同、在库且未冻结时走 LIST_UP/SOLD_MARK/CANCEL_MARK 边——无边组合（陈旧 CSV 回退、
 * 在途/已出库）一律静默跳过，listing 照记不丢数据。SOLD_MARK 携 CSV 成交价
 * （sold_price 双源：CSV>手填）。mark 的幂等键=确定性 UUID（auction+txn+batch），
 * CHAR(36) 适配；version 冲突放弃本轮标记（下次导入或手动操作自然补齐）。
 */
@Service
public class YahooMergeService {

    private final YahooListingMapper listingMapper;
    private final ItemMapper itemMapper;
    private final StockLedgerMapper ledgerMapper;
    private final AuditRecorder auditRecorder;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    public YahooMergeService(YahooListingMapper listingMapper, ItemMapper itemMapper,
            StockLedgerMapper ledgerMapper, AuditRecorder auditRecorder,
            TransactionTemplate txTemplate, Clock clock) {
        this.listingMapper = listingMapper;
        this.itemMapper = itemMapper;
        this.ledgerMapper = ledgerMapper;
        this.auditRecorder = auditRecorder;
        this.txTemplate = txTemplate;
        this.clock = clock;
    }

    /**
     * 单行合并结果：matched=管理号命中商品；itemChanged=商品侧发生实际标记/改价；
     * listingFresh=出品行首见插入（false=已有行被更新，计入批次 updated）。
     */
    public record MergeOutcome(boolean matched, boolean itemChanged, boolean listingFresh) {
    }

    public MergeOutcome merge(YahooRowParser.ParsedRow row, long batchId,
            long operatorId, String username, String displayName) {
        MergeOutcome outcome = txTemplate.execute(status ->
                mergeInTx(row, batchId, operatorId, username, displayName));
        return outcome != null ? outcome : new MergeOutcome(false, false, true);
    }

    private MergeOutcome mergeInTx(YahooRowParser.ParsedRow row, long batchId,
            long operatorId, String username, String displayName) {
        ItemEntity item = null;
        if (row.itemCode() != null) {
            item = itemMapper.selectOne(new LambdaQueryWrapper<ItemEntity>()
                    .eq(ItemEntity::getItemCode, row.itemCode()));
        }
        YahooListingEntity listing = listingMapper.selectOne(
                new LambdaQueryWrapper<YahooListingEntity>()
                        .eq(YahooListingEntity::getYahooAuctionId, row.auctionId()));
        LocalDateTime now = LocalDateTime.now(clock);

        YahooListingEntity merged = listing == null ? insertOf(row, batchId) : applyRecency(listing, row, batchId);
        if (item != null) {
            merged.setItemId(item.getId()); // 对账视图关联源（idx_item）；unmatched 行恒 NULL
        }
        int effectiveStatus = merged.getStatus();

        boolean itemChanged = false;
        if (item != null && !isFrozen(item) && item.getSaleStatus() != effectiveStatus) {
            itemChanged = tryMark(item, effectiveStatus, row, batchId,
                    operatorId, username, displayName, now);
        }

        if (listing == null) {
            listingMapper.insert(merged);
        } else {
            listingMapper.updateById(merged);
        }
        return new MergeOutcome(item != null, itemChanged, listing == null);
    }

    /** 首见行：直接落 CSV 值（status 无可回退对象）。 */
    private YahooListingEntity insertOf(YahooRowParser.ParsedRow row, long batchId) {
        YahooListingEntity listing = new YahooListingEntity();
        listing.setYahooAuctionId(row.auctionId());
        listing.setRawItemCode(row.rawItemCode());
        listing.setItemCode(row.itemCode());
        listing.setListPrice(row.listPrice());
        listing.setSoldPrice(row.soldPrice());
        listing.setStatus(row.status().id());
        listing.setListedAt(row.listedAt());
        listing.setClosedAt(row.closedAt());
        listing.setFirstSeenBatchId(batchId);
        listing.setLastSeenBatchId(batchId);
        return listing;
    }

    /** 已见行：状态 clamp + 价格/时刻 recency（事件时间新者胜，同刻=后批胜）。 */
    private YahooListingEntity applyRecency(YahooListingEntity listing,
            YahooRowParser.ParsedRow row, long batchId) {
        listing.setStatus(YahooMergePolicy.clampStatus(listing.getStatus(), row.status().id()));
        boolean newer = YahooMergePolicy.incomingIsNewer(
                YahooMergePolicy.eventTime(listing.getListedAt(), listing.getClosedAt()),
                YahooMergePolicy.eventTime(row.listedAt(), row.closedAt()));
        if (newer) {
            listing.setListPrice(row.listPrice());
            listing.setSoldPrice(row.soldPrice());
            listing.setListedAt(row.listedAt());
            listing.setClosedAt(row.closedAt());
            listing.setItemCode(row.itemCode() != null ? row.itemCode() : listing.getItemCode());
            listing.setRawItemCode(row.rawItemCode() != null ? row.rawItemCode() : listing.getRawItemCode());
        }
        listing.setLastSeenBatchId(batchId);
        return listing;
    }

    /** 作废/回收站=冻结，禁一切标记迁移（listing 照记）。 */
    private boolean isFrozen(ItemEntity item) {
        return (item.getVoided() != null && item.getVoided() == 1)
                || (item.getDeleted() != null && item.getDeleted() == 1);
    }

    /** 商品侧标记：边表校验→幂等读回→乐观锁更新→流水+审计；任何一步不合=静默跳过。 */
    private boolean tryMark(ItemEntity item, int desiredSale, YahooRowParser.ParsedRow row,
            long batchId, long operatorId, String username, String displayName, LocalDateTime now) {
        if (item.getStockStatus() == null || item.getStockStatus() != 1) {
            return false; // 边表只在库可标记；在途/已出库仅记录 listing
        }
        InventoryAction action = desiredSale == 1
                ? (item.getSaleStatus() == 0 ? InventoryAction.LIST_UP : InventoryAction.CANCEL_MARK)
                : desiredSale == 2 ? InventoryAction.SOLD_MARK : InventoryAction.CANCEL_MARK;
        InventoryStateMachine.Outcome outcome;
        try {
            outcome = InventoryStateMachine.apply(action, item.getStockStatus(), item.getSaleStatus());
        } catch (BizException e) {
            return false; // 无边=陈旧 CSV 回退（如成交→在售），保留现状
        }
        TxnType txnType = action == InventoryAction.LIST_UP ? TxnType.LIST_UP
                : action == InventoryAction.SOLD_MARK ? TxnType.SOLD_MARK : TxnType.CANCEL_MARK;
        String clientReqId = markClientReqId(row.auctionId(), txnType, batchId);
        if (ledgerMapper.selectCount(new LambdaQueryWrapper<StockLedgerEntity>()
                .eq(StockLedgerEntity::getClientReqId, clientReqId)) > 0) {
            return false; // 同键已落（重放），不重复标记
        }
        Long soldPrice = txnType == TxnType.SOLD_MARK ? row.soldPrice() : null;
        int rows = itemMapper.update(null, new LambdaUpdateWrapper<ItemEntity>()
                .eq(ItemEntity::getId, item.getId())
                .eq(ItemEntity::getVersion, item.getVersion())
                .set(ItemEntity::getStockStatus, outcome.stockTo())
                .set(ItemEntity::getSaleStatus, outcome.saleTo())
                .set(ItemEntity::getUpdatedBy, operatorId)
                .set(ItemEntity::getUpdatedAt, now)
                .set(ItemEntity::getVersion, item.getVersion() + 1)
                .set(ItemEntity::getYahooItemId, row.auctionId())
                .set(ItemEntity::getYahooLastSyncedAt, now)
                .set(soldPrice != null, ItemEntity::getSoldPrice, soldPrice));
        if (rows == 0) {
            return false; // version 冲突：本轮放弃标记（listing 照记，下次导入补齐）
        }
        StockLedgerEntity ledger = new StockLedgerEntity();
        ledger.setClientReqId(clientReqId);
        ledger.setTxnType(txnType.id());
        ledger.setItemId(item.getId());
        ledger.setItemCode(item.getItemCode());
        ledger.setOperatorId(operatorId);
        ledger.setOperatorName(displayName); // 与库存动作端点同源：ledger.operator_name=显示名
        ledger.setCreatedAt(now);
        ledger.setStockFrom(item.getStockStatus());
        ledger.setStockTo(outcome.stockTo());
        ledger.setSaleFrom(item.getSaleStatus());
        ledger.setSaleTo(outcome.saleTo());
        ledger.setQtyChange(0); // 实物未动（wh 全 NULL）
        ledgerMapper.insert(ledger);
        Map<String, Object> detail = new HashMap<>();
        detail.put("itemCode", item.getItemCode());
        detail.put("auctionId", row.auctionId());
        detail.put("batchId", batchId);
        if (soldPrice != null) {
            detail.put("soldPrice", soldPrice);
        }
        // 后台线程无 SecurityContext：显式操作人（username 与 operation_log 其余行同源）
        auditRecorder.record("YAHOO_" + txnType, "item", item.getId(), detail, operatorId, username);
        return true;
    }

    /** 确定性幂等键：auction+txn+batch 派生 UUID（适配 client_req_id CHAR(36)）。 */
    private static String markClientReqId(String auctionId, TxnType txnType, long batchId) {
        return UUID.nameUUIDFromBytes(("yho:" + auctionId + ":" + txnType.id() + ":" + batchId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
}
