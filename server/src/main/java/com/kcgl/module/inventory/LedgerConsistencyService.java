package com.kcgl.module.inventory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kcgl.module.item.ItemEntity;
import com.kcgl.module.item.ItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 对账不变量检查（M3-②，docs/01 5.3 唯一定义）：每仓 Σ(流水双向入账) ≡ COUNT(在库未删未废)。
 *
 * <p>两遍读（流水头寸聚合下推 DB 单遍扫 + 真值件一次取回），逐件比对强于仓级聚合——
 * 相互抵消的漂移（A 件幻影 +1、B 件幻影 −1 同仓）聚合恒等但逐件必报。
 * 只读事务=REPEATABLE READ 快照：动作端点并发写入不产生假漂移。
 * 每日自检 job（M3-⑦）、restore --verify、管理后台「帳実自検」共用本入口。
 */
@Service
public class LedgerConsistencyService {

    /** 仓级余额（运维可读输出）：流水净头寸合计 vs 在库件数。 */
    public record WarehouseBalance(int warehouse, long ledgerSum, long itemCount) {
    }

    /**
     * 逐件漂移：流水头寸 ≠ 行状态期望（在库件期望=恰一头寸{所在仓:+1}，其余全 0）。
     * 行字段为 null=该件在 item 表已不存在（流水指向幽灵件，本身即漂移）。
     */
    public record ItemDrift(long itemId, String itemCode, Map<Integer, Long> ledgerPositions,
            Integer itemWarehouse, Integer itemStockStatus, Integer itemVoided, Integer itemDeleted) {
    }

    /** ok=无逐件漂移（逐件一致蕴含仓级聚合一致）。 */
    public record Report(boolean ok, List<WarehouseBalance> balances, List<ItemDrift> drifts) {
    }

    /** 固定两仓：1名古屋 2福岡（docs/01 5.1）；报告恒含两仓，流水出现其他仓号也会列出。 */
    private static final List<Integer> WAREHOUSES = List.of(1, 2);

    /** 漂移件行状态补查的批大小（防极端损坏下 IN 列表过长）。 */
    private static final int ROW_BATCH = 500;

    private final StockLedgerMapper ledgerMapper;
    private final ItemMapper itemMapper;

    public LedgerConsistencyService(StockLedgerMapper ledgerMapper, ItemMapper itemMapper) {
        this.ledgerMapper = ledgerMapper;
        this.itemMapper = itemMapper;
    }

    /** 只读事务=REPEATABLE READ 快照：与动作端点的并发写入隔离，不产生假漂移。 */
    @Transactional(readOnly = true)
    public Report check() {
        // 1) 流水头寸：item → (wh → net)，仅非零行（聚合已下推 DB）
        Map<Long, Map<Integer, Long>> positions = new HashMap<>();
        for (LedgerItemPosition p : ledgerMapper.itemNetPositions()) {
            positions.computeIfAbsent(p.getItemId(), k -> new HashMap<>())
                    .put(p.getWarehouse(), p.getNet());
        }

        // 2) 真值：在库未删未废（期望头寸=恰一头寸{所在仓:+1}）
        Map<Long, ItemEntity> truth = itemMapper.selectList(new LambdaQueryWrapper<ItemEntity>()
                        .select(ItemEntity::getId, ItemEntity::getItemCode, ItemEntity::getWarehouse,
                                ItemEntity::getStockStatus, ItemEntity::getVoided, ItemEntity::getDeleted)
                        .eq(ItemEntity::getStockStatus, 1)
                        .eq(ItemEntity::getVoided, 0)
                        .eq(ItemEntity::getDeleted, 0))
                .stream().collect(Collectors.toMap(ItemEntity::getId, Function.identity()));

        // 3) 候选=头寸非零件 ∪ 真值件；不在真值的候选件补查行状态（漂移报告需展示行侧实况）
        Set<Long> candidates = new HashSet<>(positions.keySet());
        candidates.addAll(truth.keySet());
        Map<Long, ItemEntity> rows = new HashMap<>(truth);
        loadAbsentRows(candidates, truth.keySet(), rows);

        // 4) 逐件比对：头寸 ≠ 期望即漂移（含幻影头寸、缺头寸、双仓、±2 异常头寸）
        List<ItemDrift> drifts = new ArrayList<>();
        for (Long id : candidates) {
            Map<Integer, Long> actual = positions.getOrDefault(id, Map.of());
            Map<Integer, Long> expected = truth.containsKey(id)
                    ? Map.of(truth.get(id).getWarehouse(), 1L) : Map.of();
            if (!actual.equals(expected)) {
                ItemEntity row = rows.get(id);
                drifts.add(new ItemDrift(id,
                        row != null ? row.getItemCode() : null,
                        actual,
                        row != null ? row.getWarehouse() : null,
                        row != null ? row.getStockStatus() : null,
                        row != null ? row.getVoided() : null,
                        row != null ? row.getDeleted() : null));
            }
        }
        drifts.sort((a, b) -> Long.compare(a.itemId(), b.itemId()));

        // 5) 仓级聚合（由逐件头寸推导，运维可读输出）
        Map<Integer, Long> ledgerSums = new TreeMap<>();
        positions.values().forEach(m -> m.forEach((wh, net) -> ledgerSums.merge(wh, net, Long::sum)));
        Map<Integer, Long> truthCounts = new TreeMap<>();
        truth.values().forEach(item -> truthCounts.merge(item.getWarehouse(), 1L, Long::sum));
        TreeSet<Integer> whs = new TreeSet<>(WAREHOUSES);
        whs.addAll(ledgerSums.keySet());
        whs.addAll(truthCounts.keySet());
        List<WarehouseBalance> balances = whs.stream()
                .map(wh -> new WarehouseBalance(wh,
                        ledgerSums.getOrDefault(wh, 0L), truthCounts.getOrDefault(wh, 0L)))
                .toList();

        return new Report(drifts.isEmpty(), List.copyOf(balances), List.copyOf(drifts));
    }

    // ------------------------------------------------------------------ 内部

    /** 头寸非零但不在真值的件（正常为空，仅漂移时非空）分批补查行状态。 */
    private void loadAbsentRows(Set<Long> candidates, Set<Long> knownIds, Map<Long, ItemEntity> rows) {
        List<Long> absent = candidates.stream()
                .filter(id -> !knownIds.contains(id)).collect(Collectors.toCollection(ArrayList::new));
        for (int from = 0; from < absent.size(); from += ROW_BATCH) {
            List<Long> batch = absent.subList(from, Math.min(from + ROW_BATCH, absent.size()));
            itemMapper.selectList(new LambdaQueryWrapper<ItemEntity>()
                            .select(ItemEntity::getId, ItemEntity::getItemCode, ItemEntity::getWarehouse,
                                    ItemEntity::getStockStatus, ItemEntity::getVoided, ItemEntity::getDeleted)
                            .in(ItemEntity::getId, batch))
                    .forEach(item -> rows.put(item.getId(), item));
        }
    }
}
