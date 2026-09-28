package com.kcgl.module.yahoo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kcgl.module.image.FirstThumbReader;
import com.kcgl.module.item.ItemEntity;
import com.kcgl.module.item.ItemMapper;
import com.kcgl.module.yahoo.dto.PendingShipmentResponse;
import com.kcgl.module.yahoo.dto.ReconcileResponse;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 对账三活视图 + 出荷待ち（docs/01 7.2/六节）：只读拼装，无事务。
 * 商品侧条件驱动（销售/库存态×未冻结），listing 按 itemId 就近附着——
 * listing 缺行（如手动标记后 CSV 未回）时行仍展示，auctionId/closedAt 为空。
 */
@Service
public class YahooReconcileService {

    private final ItemMapper itemMapper;
    private final YahooListingMapper listingMapper;
    private final FirstThumbReader firstThumbReader;
    private final YahooProperties props;
    private final Clock clock;

    public YahooReconcileService(ItemMapper itemMapper, YahooListingMapper listingMapper,
            FirstThumbReader firstThumbReader, YahooProperties props, Clock clock) {
        this.itemMapper = itemMapper;
        this.listingMapper = listingMapper;
        this.firstThumbReader = firstThumbReader;
        this.props = props;
        this.clock = clock;
    }

    /** 三活视图：成交未出库／流拍未重上／已出库雅虎仍在售。 */
    public ReconcileResponse reconcile() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<ItemEntity> soldNotShippedItems = inStockBySale(2);
        List<ItemEntity> canceledItems = inStockBySale(3);
        List<ItemEntity> shippedItems = shippedWithLiveListing();

        return new ReconcileResponse(
                rows(soldNotShippedItems, 2, now),
                rows(canceledItems, 3, now),
                rows(shippedItems, 1, now));
    }

    /** 出荷待ち：= 视图一 + 缩略图，按货架号排序（拣货动线）。 */
    public PendingShipmentResponse pendingShipments() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<ItemEntity> items = inStockBySale(2);
        if (items.isEmpty()) {
            return new PendingShipmentResponse(0, List.of());
        }
        Map<Long, YahooListingEntity> listings = latestListingByItem(idsOf(items), 2);
        Map<Long, String> thumbs = firstThumbReader.byItemIds(idsOf(items));
        List<PendingShipmentResponse.PendingShipmentRow> rows = items.stream()
                .sorted(SHELF_ORDER)
                .map(item -> {
                    YahooListingEntity listing = listings.get(item.getId());
                    return new PendingShipmentResponse.PendingShipmentRow(
                            item.getId(), item.getItemCode(), thumbs.get(item.getId()),
                            item.getWarehouse(), item.getShelfNo(), soldPriceOf(item, listing),
                            auctionIdOf(listing), closedAtOf(listing), isDelayed(listing, now));
                })
                .toList();
        return new PendingShipmentResponse(rows.size(), rows);
    }

    // ------------------------------------------------------------- 视图查询

    /** 在库未冻结的商品按销售态取集（视图一/出荷待ち共用）。 */
    private List<ItemEntity> inStockBySale(int saleStatus) {
        return itemMapper.selectList(new LambdaQueryWrapper<ItemEntity>()
                .eq(ItemEntity::getSaleStatus, saleStatus)
                .eq(ItemEntity::getStockStatus, 1)
                .eq(ItemEntity::getVoided, 0)
                .eq(ItemEntity::getDeleted, 0));
    }

    /** 视图三（撤架）：已出库但仍有在售 listing——先取在售 listing 的 item_id 集合。 */
    private List<ItemEntity> shippedWithLiveListing() {
        List<Long> liveItemIds = listingMapper.selectList(new LambdaQueryWrapper<YahooListingEntity>()
                        .eq(YahooListingEntity::getStatus, 1)
                        .isNotNull(YahooListingEntity::getItemId))
                .stream().map(YahooListingEntity::getItemId).distinct().toList();
        if (liveItemIds.isEmpty()) {
            return List.of();
        }
        return itemMapper.selectList(new LambdaQueryWrapper<ItemEntity>()
                .in(ItemEntity::getId, liveItemIds)
                .eq(ItemEntity::getStockStatus, 2)
                .eq(ItemEntity::getVoided, 0)
                .eq(ItemEntity::getDeleted, 0));
    }

    // ------------------------------------------------------------- 行拼装

    private List<ReconcileResponse.ReconcileRow> rows(List<ItemEntity> items, int listingStatus,
            LocalDateTime now) {
        if (items.isEmpty()) {
            return List.of();
        }
        Map<Long, YahooListingEntity> listings = latestListingByItem(idsOf(items), listingStatus);
        return items.stream()
                .sorted(SHELF_ORDER)
                .map(item -> {
                    YahooListingEntity listing = listings.get(item.getId());
                    return new ReconcileResponse.ReconcileRow(
                            item.getId(), item.getItemCode(), item.getWarehouse(),
                            item.getShelfNo(), soldPriceOf(item, listing), auctionIdOf(listing),
                            closedAtOf(listing), lastSyncedAtOf(listing),
                            isDelayed(listing, now), isRecentlySynced(listing, now));
                })
                .toList();
    }

    /** 每商品取指定状态最新 listing（updatedAt 倒序首见；同刻多行走最早写入者）。 */
    private Map<Long, YahooListingEntity> latestListingByItem(List<Long> itemIds, int status) {
        return listingMapper.selectList(new LambdaQueryWrapper<YahooListingEntity>()
                        .in(YahooListingEntity::getItemId, itemIds)
                        .eq(YahooListingEntity::getStatus, status)
                        .orderByDesc(YahooListingEntity::getUpdatedAt))
                .stream()
                .collect(Collectors.toMap(YahooListingEntity::getItemId, Function.identity(),
                        (first, later) -> first));
    }

    private static List<Long> idsOf(List<ItemEntity> items) {
        return items.stream().map(ItemEntity::getId).toList();
    }

    /** 成交价双源合并展示：CSV listing 优先，手填兜底（docs/01 7.2 sold_price 优先级）。 */
    private static Long soldPriceOf(ItemEntity item, YahooListingEntity listing) {
        return listing != null && listing.getSoldPrice() != null
                ? listing.getSoldPrice() : item.getSoldPrice();
    }

    private static String auctionIdOf(YahooListingEntity listing) {
        return listing != null ? listing.getYahooAuctionId() : null;
    }

    private static LocalDateTime closedAtOf(YahooListingEntity listing) {
        return listing != null ? listing.getClosedAt() : null;
    }

    private static LocalDateTime lastSyncedAtOf(YahooListingEntity listing) {
        return listing != null ? listing.getUpdatedAt() : null;
    }

    /** 滞留红标：事件时刻（落札/终了）超阈值天数仍滞留当前视图。 */
    private boolean isDelayed(YahooListingEntity listing, LocalDateTime now) {
        if (listing == null || listing.getClosedAt() == null) {
            return false;
        }
        return listing.getClosedAt().plusDays(props.shipmentDelayWarnDays()).isBefore(now);
    }

    /** 降灰提示：listing 阈值天数内更新过（CSV 滞后期假阳性，主用于撤架视图）。 */
    private boolean isRecentlySynced(YahooListingEntity listing, LocalDateTime now) {
        return listing != null && listing.getUpdatedAt() != null
                && listing.getUpdatedAt().isAfter(now.minusDays(props.shipmentDelayWarnDays()));
    }

    /** 拣货动线：货架号自然序（null 押后），同架按管理号。 */
    private static final Comparator<ItemEntity> SHELF_ORDER =
            Comparator.comparing(ItemEntity::getShelfNo, Comparator.nullsLast(String::compareTo))
                    .thenComparing(ItemEntity::getItemCode);
}
