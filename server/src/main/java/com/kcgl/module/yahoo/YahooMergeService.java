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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 单子行合并（docs/01 7.4，D-069）：listing 行级 UPSERT + 商品侧 SOLD_MARK，
 * 一子行一事务（坏行不连坐）。
 *
 * <p>listing：uk_item_auction 定位——同拍卖既见同商品行则更新；否则收养未匹配
 * 占位行（item_id NULL，商品后录入时认领，避免孤儿行；收养限本批未触碰行——
 * まとめ売り未命中码各自落行不互相塌缩）；再无则插入。
 * 受注表=成交事实集：status 恒 2、closed_at=OrderTime（recency 判据）；
 * まとめ売り同拍卖落 N 行（每件一行）。last_seen_batch_id 恒更。
 *
 * <p>商品侧标记（docs/01 7.2 边表复用，绝不改 stock_status）：受注=成交事实，
 * 期望销售态恒 2——仅当现值≠2、在库且未冻结时走 SOLD_MARK 边（在售/未上架/
 * 取消→成交全覆盖，取消边=流拍后重新出品落札）。无边组合（已出库）静默跳过，
 * listing 照记不丢数据。SOLD_MARK 携受注价（sold_price 双源：受注>手填）。
 * mark 的幂等键=确定性 UUID（auction+item+txn+batch），CHAR(36) 适配；
 * version 冲突放弃本轮标记（下次导入或手动操作自然补齐）。
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
     * 单子行合并结果：matched=自码命中商品；itemChanged=商品侧发生实际 SOLD_MARK；
     * listingFresh=出品行首见插入（false=已有行被更新/收养，计入批次 updated）。
     */
    public record MergeOutcome(boolean matched, boolean itemChanged, boolean listingFresh) {
    }

    public MergeOutcome merge(YahooOrderParser.ParsedRow row, long batchId,
            long operatorId, String username, String displayName) {
        MergeOutcome outcome = txTemplate.execute(status ->
                mergeInTx(row, batchId, operatorId, username, displayName));
        return outcome != null ? outcome : new MergeOutcome(false, false, true);
    }

    private MergeOutcome mergeInTx(YahooOrderParser.ParsedRow row, long batchId,
            long operatorId, String username, String displayName) {
        ItemEntity item = null;
        if (row.itemCode() != null) {
            item = itemMapper.selectOne(new LambdaQueryWrapper<ItemEntity>()
                    .eq(ItemEntity::getItemCode, row.itemCode()));
        }
        YahooListingEntity target = targetRowOf(row.auctionId(), item, batchId);
        LocalDateTime now = LocalDateTime.now(clock);

        boolean fresh = target == null;
        YahooListingEntity merged = fresh ? insertOf(row, batchId) : applyRecency(target, row, batchId);
        if (item != null) {
            merged.setItemId(item.getId()); // 对账视图关联源（idx_item）；未匹配行恒 NULL
        }

        boolean itemChanged = false;
        if (item != null && !isFrozen(item) && item.getSaleStatus() != 2) {
            itemChanged = tryMark(item, row, batchId, operatorId, username, displayName, now);
        }

        if (fresh) {
            listingMapper.insert(merged);
        } else {
            listingMapper.updateById(merged);
        }
        return new MergeOutcome(item != null, itemChanged, fresh);
    }

    /**
     * (拍卖,商品) 对定位：同拍卖既见同商品行→该行；否则未匹配占位行（item_id
     * NULL）→收养（商品后录入时认领——占位行是「订单存在但自码当时未命中」的
     * 事实载体，认领保留 first_seen 不断链）；再无→null（首见插入）。
     * 占位收养仅限**本批次未触碰**的行（last_seen_batch_id≠当前批）：まとめ売り
     * 未命中 N 码逐子行落库时，兄弟子行刚插/刚收养的占位行不得被下一个兄弟复用
     * ——否则同拍卖 N 件塌缩成 1 行，违反 D-069 5「每件一行」不变量。
     */
    private YahooListingEntity targetRowOf(String auctionId, ItemEntity item, long batchId) {
        List<YahooListingEntity> existing = listingMapper.selectList(
                new LambdaQueryWrapper<YahooListingEntity>()
                        .eq(YahooListingEntity::getYahooAuctionId, auctionId));
        YahooListingEntity placeholder = null;
        for (YahooListingEntity listing : existing) {
            if (item != null && item.getId().equals(listing.getItemId())) {
                return listing;
            }
            if (listing.getItemId() == null && placeholder == null
                    && !Long.valueOf(batchId).equals(listing.getLastSeenBatchId())) {
                placeholder = listing;
            }
        }
        return placeholder; // item=null 时即占位行本体；可收养占位取最早写入者
    }

    /** 首见子行：直接落受注值（status 恒 2=成交事实集）。 */
    private YahooListingEntity insertOf(YahooOrderParser.ParsedRow row, long batchId) {
        YahooListingEntity listing = new YahooListingEntity();
        listing.setOrderId(row.orderId());
        listing.setYahooAuctionId(row.auctionId());
        listing.setRawItemCode(row.rawItemCode());
        listing.setItemCode(row.itemCode());
        listing.setSoldPrice(row.soldPrice());
        listing.setStatus(2);
        listing.setClosedAt(row.orderTime());
        listing.setFirstSeenBatchId(batchId);
        listing.setLastSeenBatchId(batchId);
        return listing;
    }

    /** 已见子行：状态恒收敛成交 + 价格/时刻 recency（事件时间新者胜，同刻=后批胜）。 */
    private YahooListingEntity applyRecency(YahooListingEntity listing,
            YahooOrderParser.ParsedRow row, long batchId) {
        listing.setStatus(YahooMergePolicy.clampStatus(listing.getStatus(), 2));
        if (YahooMergePolicy.incomingIsNewer(
                YahooMergePolicy.eventTime(listing.getListedAt(), listing.getClosedAt()),
                row.orderTime())) {
            listing.setSoldPrice(row.soldPrice());
            listing.setClosedAt(row.orderTime());
            if (row.itemCode() != null) {
                listing.setItemCode(row.itemCode());
            }
            if (row.rawItemCode() != null) {
                listing.setRawItemCode(row.rawItemCode());
            }
        }
        if (row.orderId() != null) {
            listing.setOrderId(row.orderId());
        }
        listing.setLastSeenBatchId(batchId);
        return listing;
    }

    /** 作废/回收站=冻结，禁一切标记迁移（listing 照记）。 */
    private boolean isFrozen(ItemEntity item) {
        return (item.getVoided() != null && item.getVoided() == 1)
                || (item.getDeleted() != null && item.getDeleted() == 1);
    }

    /** 商品侧 SOLD_MARK：边表校验→幂等读回→乐观锁更新→流水+审计；任何一步不合=静默跳过。 */
    private boolean tryMark(ItemEntity item, YahooOrderParser.ParsedRow row,
            long batchId, long operatorId, String username, String displayName, LocalDateTime now) {
        if (item.getStockStatus() == null || item.getStockStatus() != 1) {
            return false; // 边表只在库可标记；在途/已出库仅记录 listing
        }
        InventoryStateMachine.Outcome outcome;
        try {
            outcome = InventoryStateMachine.apply(InventoryAction.SOLD_MARK,
                    item.getStockStatus(), item.getSaleStatus());
        } catch (BizException e) {
            return false; // 无边=不可达（sale 0/1/3 全有边）；防御性兜底
        }
        String clientReqId = markClientReqId(row.auctionId(), item.getId(), batchId);
        if (ledgerMapper.selectCount(new LambdaQueryWrapper<StockLedgerEntity>()
                .eq(StockLedgerEntity::getClientReqId, clientReqId)) > 0) {
            return false; // 同键已落（重放），不重复标记
        }
        Long soldPrice = row.soldPrice(); // まとめ売り=null → 不写 item.sold_price（手填后补）
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
        ledger.setTxnType(TxnType.SOLD_MARK.id());
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
        } else {
            detail.put("multiItem", true); // まとめ売り单价未分割，sold_price 待手填
        }
        // 后台线程无 SecurityContext：显式操作人（username 与 operation_log 其余行同源）
        auditRecorder.record("YAHOO_SOLD_MARK", "item", item.getId(), detail, operatorId, username);
        return true;
    }

    /** 确定性幂等键：auction+item+batch 派生 UUID（适配 client_req_id CHAR(36)；
     *  含 itemId——まとめ売り同拍卖同批次多件各自独立键）。 */
    private static String markClientReqId(String auctionId, long itemId, long batchId) {
        return UUID.nameUUIDFromBytes(("yho:" + auctionId + ":" + itemId + ":"
                + TxnType.SOLD_MARK.id() + ":" + batchId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
}
