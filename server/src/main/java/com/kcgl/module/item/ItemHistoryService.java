package com.kcgl.module.item;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kcgl.module.inventory.StockLedgerEntity;
import com.kcgl.module.inventory.StockLedgerMapper;
import com.kcgl.module.item.dto.ItemLedgerListResponse;
import com.kcgl.module.item.dto.YahooListingListResponse;
import com.kcgl.module.yahoo.YahooListingEntity;
import com.kcgl.module.yahoo.YahooListingMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 单件历史（GET /api/items/{id}/ledgers、/{id}/yahoo-listings，D-061）。
 * 404 语义复用 ItemService.getById（软删=不存在；作废件可读——终态件的历史仍需可查）。
 * ledgers 按 id 倒序（毫秒并列时 id=写入序决胜负）；yahoo-listings 按 closed_at 倒序
 * （受注导入的 listed_at 恒 NULL，closed_at=受注时刻，D-069）。
 */
@Service
public class ItemHistoryService {

    private final ItemService itemService;
    private final StockLedgerMapper ledgerMapper;
    private final YahooListingMapper yahooListingMapper;

    public ItemHistoryService(ItemService itemService, StockLedgerMapper ledgerMapper,
            YahooListingMapper yahooListingMapper) {
        this.itemService = itemService;
        this.ledgerMapper = ledgerMapper;
        this.yahooListingMapper = yahooListingMapper;
    }

    public ItemLedgerListResponse ledgers(long itemId) {
        itemService.getById(itemId);
        List<ItemLedgerListResponse.Row> rows = ledgerMapper.selectList(
                        new LambdaQueryWrapper<StockLedgerEntity>()
                                .eq(StockLedgerEntity::getItemId, itemId)
                                .orderByDesc(StockLedgerEntity::getId)).stream()
                .map(l -> new ItemLedgerListResponse.Row(
                        l.getId(), l.getTxnType(), l.getWhFrom(), l.getWhTo(), l.getQtyChange(),
                        l.getStockFrom(), l.getStockTo(), l.getSaleFrom(), l.getSaleTo(),
                        l.getReason(), l.getReasonCode(), l.getReasonParams(),
                        l.getOperatorName(), l.getCreatedAt()))
                .toList();
        return new ItemLedgerListResponse(rows);
    }

    public YahooListingListResponse yahooListings(long itemId) {
        itemService.getById(itemId);
        List<YahooListingListResponse.Row> rows = yahooListingMapper.selectList(
                        new LambdaQueryWrapper<YahooListingEntity>()
                                .eq(YahooListingEntity::getItemId, itemId)
                                .orderByDesc(YahooListingEntity::getClosedAt)
                                .orderByDesc(YahooListingEntity::getId)).stream()
                .map(l -> new YahooListingListResponse.Row(
                        l.getId(), l.getOrderId(), l.getYahooAuctionId(), l.getListPrice(),
                        l.getSoldPrice(), l.getStatus(), l.getListedAt(), l.getClosedAt()))
                .toList();
        return new YahooListingListResponse(rows);
    }
}
