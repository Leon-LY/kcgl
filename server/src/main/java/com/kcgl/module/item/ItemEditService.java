package com.kcgl.module.item;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kcgl.common.audit.AuditRecorder;
import com.kcgl.common.sse.SseHub;
import com.kcgl.common.sse.SyncEvent;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.dict.PriceBandService;
import com.kcgl.module.dict.VenueMapper;
import com.kcgl.module.item.dto.UpdateItemRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 商品编辑（PUT /api/items/{id}，D-063 snapshot 单模式）。
 *
 * 可改列全量覆盖（可选字段 null=清空，补 D-035「重录无法清空费用」缺口）；
 * 号内快照列（venue_code/year_code/buy_month/seq_*、item_code）恒不动，管理号不重算。
 * 仓库契约 W（D-066）：非在途（在库/已出库）时仓值变化 → 409013 引导
 * /inventory/transfer（台账路径）；同值提交=无操作放行（A16 在库补录费用仍可编辑）。
 * priceBandCode 即使价格未变也重推导（档位表调整后编辑即对齐）。
 * total_cost/profit 为生成列（FieldStrategy NEVER）不进 SET，由 DB 重算。
 * 审计 detail=before/after 快照（LinkedHashMap——Map.of 遇 null 值 NPE）。
 */
@Service
public class ItemEditService {

    private final ItemMapper itemMapper;
    private final VenueMapper venueMapper;
    private final PriceBandService priceBandService;
    private final AuditRecorder auditRecorder;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final SseHub sseHub;

    public ItemEditService(ItemMapper itemMapper, VenueMapper venueMapper,
            PriceBandService priceBandService, AuditRecorder auditRecorder,
            TransactionTemplate txTemplate, Clock clock, SseHub sseHub) {
        this.itemMapper = itemMapper;
        this.venueMapper = venueMapper;
        this.priceBandService = priceBandService;
        this.auditRecorder = auditRecorder;
        this.txTemplate = txTemplate;
        this.clock = clock;
        this.sseHub = sseHub;
    }

    public ItemEntity update(long itemId, UpdateItemRequest req, long operatorId, String operatorName) {
        ItemEntity updated = txTemplate.execute(status -> {
            ItemEntity item = requireEditableItem(itemId);
            requireVenueExists(req.venueId());
            requireWarehouseEditable(item, req.warehouse());
            String bandCode = priceBandService.match(req.purchasePrice()).code();

            Map<String, Object> before = editSnapshot(item);
            LocalDateTime now = LocalDateTime.now(clock);
            int rows = itemMapper.update(null, new LambdaUpdateWrapper<ItemEntity>()
                    .eq(ItemEntity::getId, itemId)
                    .eq(ItemEntity::getVersion, req.version())
                    .set(ItemEntity::getVenueId, req.venueId())
                    .set(ItemEntity::getBuyDate, req.buyDate())
                    .set(ItemEntity::getPurchasePrice, req.purchasePrice())
                    .set(ItemEntity::getPriceBandCode, bandCode)
                    .set(ItemEntity::getFee, req.fee())
                    .set(ItemEntity::getShippingFee, req.shippingFee())
                    .set(ItemEntity::getTax, req.tax())
                    .set(ItemEntity::getPhotoDate, req.photoDate())
                    .set(ItemEntity::getWarehouse, req.warehouse())
                    .set(ItemEntity::getShelfNo, req.shelfNo())
                    .set(ItemEntity::getWarehouseInDate, req.warehouseInDate())
                    .set(ItemEntity::getGroupNo, req.groupNo())
                    .set(ItemEntity::getRemark, req.remark())
                    .set(ItemEntity::getItemName, req.itemName())
                    .set(ItemEntity::getCategory, req.category())
                    .set(ItemEntity::getAuthorKiln, req.authorKiln())
                    .set(ItemEntity::getSizeText, req.sizeText())
                    .set(ItemEntity::getWeightG, req.weightG())
                    .set(ItemEntity::getSalesChannel, req.salesChannel())
                    .set(ItemEntity::getUpdatedBy, operatorId)
                    .set(ItemEntity::getUpdatedAt, now)
                    .set(ItemEntity::getVersion, req.version() + 1));
            if (rows == 0) {
                throw new BizException(ErrorCode.CONFLICT);
            }
            ItemEntity after = itemMapper.selectById(itemId);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("before", before);
            detail.put("after", editSnapshot(after));
            auditRecorder.record("ITEM_UPDATE", "item", itemId, detail);
            return after;
        });
        sseHub.broadcast(SyncEvent.TYPE_ITEM, updated.getItemCode(), operatorId);
        return updated;
    }

    /** 软删件=不存在（404）；作废件=终态，引导作废重录（409008——不与 ItemService.requireLiveItem 的 409006 混用）。 */
    private ItemEntity requireEditableItem(long itemId) {
        ItemEntity item = itemMapper.selectById(itemId);
        if (item == null || (item.getDeleted() != null && item.getDeleted() == 1)) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        if (item.getVoided() != null && item.getVoided() == 1) {
            throw new BizException(ErrorCode.INVALID_TRANSITION, "取り消された商品は編集できません。再登録してください");
        }
        return item;
    }

    /** 存在性校验（D-031：停用会场的历史件仍可指向——只查存在，不查 enabled）。 */
    private void requireVenueExists(Long venueId) {
        if (venueMapper.selectById(venueId) == null) {
            throw new BizException(ErrorCode.VENUE_NOT_FOUND);
        }
    }

    /** 契约 W：在途（stock_status=0）可改预计仓库；非在途仓值变化一律走移动台账。 */
    private void requireWarehouseEditable(ItemEntity item, Integer reqWarehouse) {
        if (item.getStockStatus() != null && item.getStockStatus() != 0
                && !Objects.equals(item.getWarehouse(), reqWarehouse)) {
            throw new BizException(ErrorCode.WAREHOUSE_TRANSFER_REQUIRED);
        }
    }

    private Map<String, Object> editSnapshot(ItemEntity item) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("venueId", item.getVenueId());
        snapshot.put("buyDate", item.getBuyDate());
        snapshot.put("purchasePrice", item.getPurchasePrice());
        snapshot.put("priceBandCode", item.getPriceBandCode());
        snapshot.put("fee", item.getFee());
        snapshot.put("shippingFee", item.getShippingFee());
        snapshot.put("tax", item.getTax());
        snapshot.put("photoDate", item.getPhotoDate());
        snapshot.put("warehouse", item.getWarehouse());
        snapshot.put("shelfNo", item.getShelfNo());
        snapshot.put("warehouseInDate", item.getWarehouseInDate());
        snapshot.put("groupNo", item.getGroupNo());
        snapshot.put("remark", item.getRemark());
        snapshot.put("itemName", item.getItemName());
        snapshot.put("category", item.getCategory());
        snapshot.put("authorKiln", item.getAuthorKiln());
        snapshot.put("sizeText", item.getSizeText());
        snapshot.put("weightG", item.getWeightG());
        snapshot.put("salesChannel", item.getSalesChannel());
        return snapshot;
    }
}
