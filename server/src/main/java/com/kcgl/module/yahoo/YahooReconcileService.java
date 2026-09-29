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
 * 对账三活视图 + 出荷待ち（docs/01 7.2，D-069 语义重校）：只读拼装，无事务。
 * 商品侧条件驱动（销售/库存态×未冻结），listing 按 itemId 就近附着——
 * listing 缺行时行仍展示，orderId/auctionId/closedAt 为空。
 *
 * <p>视图一/出荷待ち（成交未出库）：受注表正是其数据源。视图二（流拍未重上）：
 * 手动取消标记（mark-canceled）驱动。视图三（已出库仍在售=撤架）：手动上架标记
 * （mark-listed）驱动——受注表无在售信息，「仍在售」的唯一系统事实是 sale_status=1；
 * lastSyncedAt 口径=最近一次受注导入完成时刻（导入后仍未成交=通过一次校验）。
 */
@Service
public class YahooReconcileService {

    private final ItemMapper itemMapper;
    private final YahooListingMapper listingMapper;
    private final YahooImportBatchMapper batchMapper;
    private final FirstThumbReader firstThumbReader;
    private final YahooProperties props;
    private final Clock clock;

    public YahooReconcileService(ItemMapper itemMapper, YahooListingMapper listingMapper,
            YahooImportBatchMapper batchMapper, FirstThumbReader firstThumbReader,
            YahooProperties props, Clock clock) {
        this.itemMapper = itemMapper;
        this.listingMapper = listingMapper;
        this.batchMapper = batchMapper;
        this.firstThumbReader = firstThumbReader;
        this.props = props;
        this.clock = clock;
    }

    /** 三活视图：成交未出库／流拍未重上／已出库仍在售（撤架）。 */
    public ReconcileResponse reconcile() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<ItemEntity> soldNotShippedItems = inStockBySale(2);
        List<ItemEntity> canceledItems = inStockBySale(3);
        List<ItemEntity> shippedStillListedItems = shippedStillListed();
        YahooImportBatchEntity latestBatch = latestDoneBatch();

        return new ReconcileResponse(
                rows(soldNotShippedItems, 2, null, now),
                rows(canceledItems, 3, null, now),
                rows(shippedStillListedItems, 1, finishedAtOf(latestBatch), now));
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
                            orderIdOf(listing), auctionIdOf(listing), closedAtOf(listing),
                            isDelayed(listing, now));
                })
                .toList();
        return new PendingShipmentResponse(rows.size(), rows);
    }

    // ------------------------------------------------------------- 视图查询

    /** 大盘雅虎指标（M5-③ stats）：与三活视图同源的条件计数（口径唯一定义在此）。 */
    public long countSoldNotShipped() {
        return countOf(inStockBySaleWrapper(2));
    }

    public long countCanceledNotRelisted() {
        return countOf(inStockBySaleWrapper(3));
    }

    public long countWithdrawNeeded() {
        return countOf(shippedStillListedWrapper());
    }

    /**
     * 最近一次成功受注导入（大盘数据新鲜度：完成时刻+文件名；无成功批次=null）。
     * 与 reconcile 视图三的 lastSyncedAt 兜底同源。
     */
    public YahooImportBatchEntity latestDoneBatch() {
        List<YahooImportBatchEntity> latest = batchMapper.selectList(
                new LambdaQueryWrapper<YahooImportBatchEntity>()
                        .eq(YahooImportBatchEntity::getStatus, YahooImportBatchEntity.STATUS_DONE)
                        .isNotNull(YahooImportBatchEntity::getFinishedAt)
                        .orderByDesc(YahooImportBatchEntity::getFinishedAt)
                        .last("LIMIT 1"));
        return latest.isEmpty() ? null : latest.get(0);
    }

    private long countOf(LambdaQueryWrapper<ItemEntity> wrapper) {
        Long count = itemMapper.selectCount(wrapper);
        return count == null ? 0 : count;
    }

    /** 在库未冻结的商品按销售态取集（视图一/二/出荷待ち共用）。 */
    private List<ItemEntity> inStockBySale(int saleStatus) {
        return itemMapper.selectList(inStockBySaleWrapper(saleStatus));
    }

    private LambdaQueryWrapper<ItemEntity> inStockBySaleWrapper(int saleStatus) {
        return new LambdaQueryWrapper<ItemEntity>()
                .eq(ItemEntity::getSaleStatus, saleStatus)
                .eq(ItemEntity::getStockStatus, 1)
                .eq(ItemEntity::getVoided, 0)
                .eq(ItemEntity::getDeleted, 0);
    }

    /** 视图三（撤架）：已出库但仍标记在售（手动 LIST_UP 是在售的唯一系统事实）。 */
    private List<ItemEntity> shippedStillListed() {
        return itemMapper.selectList(shippedStillListedWrapper());
    }

    private LambdaQueryWrapper<ItemEntity> shippedStillListedWrapper() {
        return new LambdaQueryWrapper<ItemEntity>()
                .eq(ItemEntity::getSaleStatus, 1)
                .eq(ItemEntity::getStockStatus, 2)
                .eq(ItemEntity::getVoided, 0)
                .eq(ItemEntity::getDeleted, 0);
    }

    private static LocalDateTime finishedAtOf(YahooImportBatchEntity batch) {
        return batch == null ? null : batch.getFinishedAt();
    }

    // ------------------------------------------------------------- 行拼装

    private List<ReconcileResponse.ReconcileRow> rows(List<ItemEntity> items, int listingStatus,
            LocalDateTime fallbackSyncedAt, LocalDateTime now) {
        if (items.isEmpty()) {
            return List.of();
        }
        Map<Long, YahooListingEntity> listings = latestListingByItem(idsOf(items), listingStatus);
        return items.stream()
                .sorted(SHELF_ORDER)
                .map(item -> {
                    YahooListingEntity listing = listings.get(item.getId());
                    LocalDateTime lastSyncedAt = lastSyncedAtOf(listing, fallbackSyncedAt);
                    return new ReconcileResponse.ReconcileRow(
                            item.getId(), item.getItemCode(), item.getWarehouse(),
                            item.getShelfNo(), soldPriceOf(item, listing),
                            orderIdOf(listing), auctionIdOf(listing), closedAtOf(listing),
                            lastSyncedAt, isDelayed(listing, now),
                            isRecentlySynced(lastSyncedAt, now));
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

    /** 成交价双源合并展示：受注 listing 优先，手填兜底（docs/01 7.2 sold_price 优先级）。 */
    private static Long soldPriceOf(ItemEntity item, YahooListingEntity listing) {
        return listing != null && listing.getSoldPrice() != null
                ? listing.getSoldPrice() : item.getSoldPrice();
    }

    private static String orderIdOf(YahooListingEntity listing) {
        return listing != null ? listing.getOrderId() : null;
    }

    private static String auctionIdOf(YahooListingEntity listing) {
        return listing != null ? listing.getYahooAuctionId() : null;
    }

    private static LocalDateTime closedAtOf(YahooListingEntity listing) {
        return listing != null ? listing.getClosedAt() : null;
    }

    /** listing 缺行（视图二/三常态）时取视图级兜底（受注导入时刻）。 */
    private static LocalDateTime lastSyncedAtOf(YahooListingEntity listing,
            LocalDateTime fallbackSyncedAt) {
        return listing != null && listing.getUpdatedAt() != null
                ? listing.getUpdatedAt() : fallbackSyncedAt;
    }

    /** 滞留红标：受注（成交）时刻超阈值天数仍滞留当前视图。 */
    private boolean isDelayed(YahooListingEntity listing, LocalDateTime now) {
        if (listing == null || listing.getClosedAt() == null) {
            return false;
        }
        return listing.getClosedAt().plusDays(props.shipmentDelayWarnDays()).isBefore(now);
    }

    /** 降灰提示：同步口径时刻在阈值天数内（主用于撤架视图的假阳性提示）。 */
    private boolean isRecentlySynced(LocalDateTime lastSyncedAt, LocalDateTime now) {
        return lastSyncedAt != null
                && lastSyncedAt.isAfter(now.minusDays(props.shipmentDelayWarnDays()));
    }

    /** 拣货动线：货架号自然序（null 押后），同架按管理号。 */
    private static final Comparator<ItemEntity> SHELF_ORDER =
            Comparator.comparing(ItemEntity::getShelfNo, Comparator.nullsLast(String::compareTo))
                    .thenComparing(ItemEntity::getItemCode);
}
