package com.kcgl.module.stocktake;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kcgl.common.audit.AuditRecorder;
import com.kcgl.common.sse.SseHub;
import com.kcgl.common.sse.SyncEvent;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.image.FirstThumbReader;
import com.kcgl.module.inventory.InventoryAction;
import com.kcgl.module.inventory.InventoryStateMachine;
import com.kcgl.module.inventory.StockLedgerEntity;
import com.kcgl.module.inventory.StockLedgerMapper;
import com.kcgl.module.inventory.TxnType;
import com.kcgl.module.inventory.dto.ActionResult;
import com.kcgl.module.item.ItemEntity;
import com.kcgl.module.item.ItemMapper;
import com.kcgl.module.item.dto.ItemResponse;
import com.kcgl.module.stocktake.dto.StocktakeCreateRequest;
import com.kcgl.module.stocktake.dto.StocktakeDiffActionRequest;
import com.kcgl.module.stocktake.dto.StocktakeDiffActionResponse;
import com.kcgl.module.stocktake.dto.StocktakeDiffListResponse;
import com.kcgl.module.stocktake.dto.StocktakeDiffRowResponse;
import com.kcgl.module.stocktake.dto.StocktakeListResponse;
import com.kcgl.module.stocktake.dto.StocktakeScanResultResponse;
import com.kcgl.module.stocktake.dto.StocktakeSummaryResponse;
import com.kcgl.module.user.SysUserEntity;
import com.kcgl.module.user.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 盘点全流程（M3-⑥，docs/01 7.3 唯一定义）：发起（一仓一进行中单）→ 扫码照记
 * （重复不报错/卡内警示由前端派生）→ close 冻结期望集合并生成差异表 → 逐条
 * CONFIRM（STOCKTAKE_ADJUST 流水）/IGNORE → 全处理完自动转已确认。
 *
 * <p>并发纪律：全部写操作（scan/close/cancel/resolve）先对盘点单行 FOR UPDATE——
 * 同单操作串行化后，close 的差异计算与「全处理完转已确认」计数都建立在锁内一致
 * 快照上；跨单只共享 item 行的乐观锁（version 冲突→事务外换新事务重读，docs/01
 * 7.1 C1 同纪律：REPEATABLE READ 下事务内重读是陈旧快照）。
 *
 * <p>差异分类（close 时刻快照）：盘亏=期望有未扫到；盘盈=扫到但系统非在库；
 * 仓错=在库但系统仓≠盘点仓；冻结品=作废/回收站件被扫到（禁 CONFIRM）。差异行
 * 以「盘点期间（发起→close）有变动流水」标注辅助裁决——close 后复审期间的自然
 * 变动由状态机边表与乐观锁把守（非法态 409008），语义裁决权在人。
 *
 * <p>CONFIRM 入账规则（与 M3-② 对账不变量严格一致）：wh_from/wh_to 一律引用
 * 确认时刻的商品现仓——盘亏记 (现仓,−1)；盘盈记 (盘点仓,+1) 并把商品仓改写为
 * 盘点仓（扫码是实物位置的证据）；仓错记 (现仓,−1)(盘点仓,+1)。close→确认之间
 * 发生过调拨时按现仓入账，账实恒等。
 */
@Service
public class StocktakeService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int STATUS_ACTIVE = 0;
    private static final int STATUS_PENDING_CONFIRM = 1;
    private static final int STATUS_CONFIRMED = 2;
    private static final int STATUS_CANCELLED = 3;

    /** 差异行「盘点期间有变动」标注值（前端按 note 非空渲染固定文案）。 */
    static final String NOTE_CHANGED = "棚卸期間中に変動あり";

    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StocktakeMapper stocktakeMapper;
    private final StocktakeScanMapper scanMapper;
    private final StocktakeDiffMapper diffMapper;
    private final ItemMapper itemMapper;
    private final StockLedgerMapper ledgerMapper;
    private final SysUserMapper userMapper;
    private final FirstThumbReader firstThumbReader;
    private final AuditRecorder auditRecorder;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final SseHub sseHub;

    public StocktakeService(StocktakeMapper stocktakeMapper, StocktakeScanMapper scanMapper,
            StocktakeDiffMapper diffMapper, ItemMapper itemMapper, StockLedgerMapper ledgerMapper,
            SysUserMapper userMapper, FirstThumbReader firstThumbReader, AuditRecorder auditRecorder,
            TransactionTemplate txTemplate, Clock clock, SseHub sseHub) {
        this.stocktakeMapper = stocktakeMapper;
        this.scanMapper = scanMapper;
        this.diffMapper = diffMapper;
        this.itemMapper = itemMapper;
        this.ledgerMapper = ledgerMapper;
        this.userMapper = userMapper;
        this.firstThumbReader = firstThumbReader;
        this.auditRecorder = auditRecorder;
        this.txTemplate = txTemplate;
        this.clock = clock;
        this.sseHub = sseHub;
    }

    // ------------------------------------------------------------------ 发起

    /** 发起盘点：同仓已有进行中单 → 409009；单号 PD+日期(JST)+两位序号。 */
    public StocktakeSummaryResponse create(StocktakeCreateRequest req, long operatorId) {
        int warehouse = req.warehouse();
        StocktakeEntity st = txTemplate.execute(status -> {
            // 一仓一进行中单：FOR UPDATE 探测串行化「查无→插入」（当日序号计数同受保护）
            if (!stocktakeMapper.lockActiveIds(warehouse).isEmpty()) {
                throw new BizException(ErrorCode.STOCKTAKE_ACTIVE_EXISTS);
            }
            String prefix = "PD" + LocalDate.now(clock).format(NO_FORMAT) + "-";
            Long todayCount = stocktakeMapper.selectCount(new LambdaQueryWrapper<StocktakeEntity>()
                    .likeRight(StocktakeEntity::getStocktakeNo, prefix));
            StocktakeEntity entity = new StocktakeEntity();
            entity.setStocktakeNo(prefix + String.format(Locale.ROOT, "%02d",
                    (todayCount == null ? 0 : todayCount) + 1));
            entity.setWarehouse(warehouse);
            entity.setStatus(STATUS_ACTIVE);
            entity.setScannedCount(0);
            entity.setCreatedBy(operatorId);
            entity.setCreatedAt(LocalDateTime.now(clock));
            stocktakeMapper.insert(entity);
            auditRecorder.record("STOCKTAKE_CREATE", "stocktake", entity.getId(),
                    Map.of("stocktakeNo", entity.getStocktakeNo(), "warehouse", warehouse));
            return entity;
        });
        sseHub.broadcast(SyncEvent.TYPE_STOCKTAKE, st.getStocktakeNo(), operatorId);
        return summaryOf(st, null, operatorId);
    }

    // ------------------------------------------------------------------ 查询

    /** 列表（创建时间倒序）：pendingDiffCount 无表列，close 后按差异表 COUNT 派生。 */
    public StocktakeListResponse list(int page, int size, Integer status, long currentUserId) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<StocktakeEntity> result = stocktakeMapper.selectPage(new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<StocktakeEntity>()
                        .eq(status != null, StocktakeEntity::getStatus, status)
                        .orderByDesc(StocktakeEntity::getId));
        List<StocktakeEntity> rows = result.getRecords();
        List<Long> closedIds = rows.stream()
                .filter(st -> st.getStatus() >= STATUS_PENDING_CONFIRM)
                .map(StocktakeEntity::getId)
                .toList();
        Map<Long, Integer> pending = pendingDiffCounts(closedIds);
        Map<Long, String> names = displayNames(rows.stream()
                .flatMap(st -> Stream.of(st.getCreatedBy(), st.getClosedBy())));
        List<StocktakeSummaryResponse> summaries = rows.stream()
                .map(st -> StocktakeSummaryResponse.of(st,
                        st.getStatus() >= STATUS_PENDING_CONFIRM
                                ? pending.getOrDefault(st.getId(), 0) : null,
                        names.get(st.getCreatedBy()), names.get(st.getClosedBy()), currentUserId))
                .toList();
        return new StocktakeListResponse(result.getTotal(), safePage, safeSize, summaries);
    }

    /** 单条详情（扫码页/差异页头部计数）。 */
    public StocktakeSummaryResponse detail(long id, long currentUserId) {
        StocktakeEntity st = requireStocktake(id);
        Integer pending = st.getStatus() >= STATUS_PENDING_CONFIRM
                ? pendingDiffCounts(List.of(id)).getOrDefault(id, 0) : null;
        return summaryOf(st, pending, currentUserId);
    }

    /** 差异表分页：待确认优先（confirm_status 升序→id 升序）。 */
    public StocktakeDiffListResponse diffs(long stocktakeId, int page, int size, Integer confirmStatus) {
        requireStocktake(stocktakeId);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<StocktakeDiffEntity> result = diffMapper.selectPage(new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<StocktakeDiffEntity>()
                        .eq(StocktakeDiffEntity::getStocktakeId, stocktakeId)
                        .eq(confirmStatus != null, StocktakeDiffEntity::getConfirmStatus, confirmStatus)
                        .orderByAsc(StocktakeDiffEntity::getConfirmStatus)
                        .orderByAsc(StocktakeDiffEntity::getId));
        List<Long> itemIds = result.getRecords().stream().map(StocktakeDiffEntity::getItemId).toList();
        Map<Long, String> thumbs = firstThumbReader.byItemIds(itemIds);
        List<StocktakeDiffRowResponse> rows = result.getRecords().stream()
                .map(diff -> StocktakeDiffRowResponse.from(diff, thumbs.get(diff.getItemId())))
                .toList();
        return new StocktakeDiffListResponse(result.getTotal(), safePage, safeSize, rows);
    }

    // ------------------------------------------------------------------ 扫码

    /**
     * 盘点扫码：照记不拦（他仓/冻结/非在库由前端卡内警示，docs/01 7.3）；
     * 同单同件重复扫 → repeated=true 200 不报错；扫码留痕=stocktake_scan 行本身
     * （scanned_by/scanned_at，双留痕），不逐条写 operation_log。
     */
    public StocktakeScanResultResponse scan(long stocktakeId, String code, long operatorId) {
        String normalized = Normalizer.normalize(code.trim(), Normalizer.Form.NFKC)
                .toUpperCase(Locale.ROOT);
        ItemEntity item = itemMapper.selectOne(new LambdaQueryWrapper<ItemEntity>()
                .eq(ItemEntity::getItemCode, normalized));
        if (item == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        ScanOutcome outcome = txTemplate.execute(status -> {
            StocktakeEntity st = stocktakeMapper.lockById(stocktakeId);
            if (st == null) {
                throw new BizException(ErrorCode.NOT_FOUND);
            }
            if (st.getStatus() != STATUS_ACTIVE) {
                throw new BizException(ErrorCode.STOCKTAKE_STATUS_INVALID);
            }
            Long existing = scanMapper.selectCount(new LambdaQueryWrapper<StocktakeScanEntity>()
                    .eq(StocktakeScanEntity::getStocktakeId, stocktakeId)
                    .eq(StocktakeScanEntity::getItemId, item.getId()));
            if (existing != null && existing > 0) {
                return new ScanOutcome(true, st);
            }
            StocktakeScanEntity scan = new StocktakeScanEntity();
            scan.setStocktakeId(stocktakeId);
            scan.setItemId(item.getId());
            scan.setItemCode(item.getItemCode());
            scan.setScannedBy(operatorId);
            scan.setScannedAt(LocalDateTime.now(clock));
            scanMapper.insert(scan);
            stocktakeMapper.update(null, new LambdaUpdateWrapper<StocktakeEntity>()
                    .eq(StocktakeEntity::getId, stocktakeId)
                    .setSql("scanned_count = scanned_count + 1"));
            return new ScanOutcome(false, st);
        });
        if (!outcome.repeated()) {
            sseHub.broadcast(SyncEvent.TYPE_STOCKTAKE, outcome.stocktake().getStocktakeNo(), operatorId);
        }
        String thumbUrl = firstThumbReader.byItemIds(List.of(item.getId())).get(item.getId());
        return new StocktakeScanResultResponse(outcome.repeated(), ItemResponse.from(item), thumbUrl);
    }

    // ------------------------------------------------------------------ close / 撤销

    /**
     * close：冻结期望集合（该仓在库未删未废）→ 生成差异表 → 单据转待确认。
     * 期望集合取 close 时刻现态——盘点期间的自然变动（调拨/卖出）已被现态吸收，
     * 落入差异的仅剩「实物与系统对不上」的部分，由人工裁决。
     */
    public StocktakeSummaryResponse close(long stocktakeId, long operatorId) {
        StocktakeSummaryResponse summary = txTemplate.execute(status ->
                closeInTx(stocktakeId, operatorId));
        sseHub.broadcast(SyncEvent.TYPE_STOCKTAKE, summary.stocktakeNo(), operatorId);
        return summary;
    }

    private StocktakeSummaryResponse closeInTx(long stocktakeId, long operatorId) {
        StocktakeEntity st = stocktakeMapper.lockById(stocktakeId);
        if (st == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        if (st.getStatus() != STATUS_ACTIVE) {
            throw new BizException(ErrorCode.STOCKTAKE_STATUS_INVALID);
        }
        // 期望集合（close 时刻冻结）：仅取 id+管理号，避免整仓实体进内存；TreeMap=稳定插入序
        Map<Long, String> expected = new TreeMap<>(itemMapper.selectList(new LambdaQueryWrapper<ItemEntity>()
                        .select(ItemEntity::getId, ItemEntity::getItemCode)
                        .eq(ItemEntity::getWarehouse, st.getWarehouse())
                        .eq(ItemEntity::getStockStatus, 1)
                        .eq(ItemEntity::getVoided, 0)
                        .eq(ItemEntity::getDeleted, 0))
                .stream()
                .collect(Collectors.toMap(ItemEntity::getId, ItemEntity::getItemCode)));
        List<StocktakeScanEntity> scans = scanMapper.selectList(new LambdaQueryWrapper<StocktakeScanEntity>()
                .eq(StocktakeScanEntity::getStocktakeId, stocktakeId));
        Set<Long> scannedIds = scans.stream().map(StocktakeScanEntity::getItemId).collect(Collectors.toSet());
        Map<Long, ItemEntity> scannedItems = scannedIds.isEmpty() ? Map.of()
                : itemMapper.selectByIds(scannedIds).stream()
                        .collect(Collectors.toMap(ItemEntity::getId, Function.identity()));

        List<StocktakeDiffEntity> diffs = new ArrayList<>();
        for (ItemEntity item : scannedItems.values()) {
            if (expected.containsKey(item.getId())) {
                continue; // 期望集合内：对上，无差异
            }
            StocktakeDiffEntity diff = baseDiff(stocktakeId, item.getId(), item.getItemCode());
            boolean frozen = (item.getVoided() != null && item.getVoided() == 1)
                    || (item.getDeleted() != null && item.getDeleted() == 1);
            if (frozen) {
                diff.setDiffType(StocktakeDiffEntity.TYPE_FROZEN);
            } else if (item.getStockStatus() == null || item.getStockStatus() != 1) {
                diff.setDiffType(StocktakeDiffEntity.TYPE_GAIN);
                diff.setActualWh(st.getWarehouse());
            } else if (!Objects.equals(item.getWarehouse(), st.getWarehouse())) {
                diff.setDiffType(StocktakeDiffEntity.TYPE_WH_MISMATCH);
                diff.setExpectedWh(item.getWarehouse());
                diff.setActualWh(st.getWarehouse());
            } else {
                // 锁内一致快照下不可达（在库+本仓+未删未废 ⇒ 必在期望集合）——防御跳过
                continue;
            }
            diffs.add(diff);
        }
        for (Map.Entry<Long, String> expectedItem : expected.entrySet()) {
            if (scannedIds.contains(expectedItem.getKey())) {
                continue;
            }
            StocktakeDiffEntity diff = baseDiff(stocktakeId, expectedItem.getKey(), expectedItem.getValue());
            diff.setDiffType(StocktakeDiffEntity.TYPE_LOSS);
            diff.setExpectedWh(st.getWarehouse());
            diffs.add(diff);
        }
        diffs.sort(Comparator.comparing(StocktakeDiffEntity::getItemId));
        markChangedDuringStocktake(st, diffs);

        LocalDateTime now = LocalDateTime.now(clock);
        for (StocktakeDiffEntity diff : diffs) {
            diffMapper.insert(diff);
        }
        st.setStatus(STATUS_PENDING_CONFIRM);
        st.setExpectedCount(expected.size());
        st.setScannedCount(scans.size());
        st.setDiffCount(diffs.size());
        st.setClosedAt(now);
        st.setClosedBy(operatorId);
        stocktakeMapper.updateById(st);
        Map<String, Object> detail = new HashMap<>();
        detail.put("stocktakeNo", st.getStocktakeNo());
        detail.put("expected", expected.size());
        detail.put("scanned", scans.size());
        detail.put("diffCount", diffs.size());
        auditRecorder.record("STOCKTAKE_CLOSE", "stocktake", st.getId(), detail);
        return summaryOf(st, diffs.size(), operatorId);
    }

    /** 发起人撤自己的单（仅进行中可撤；服务端强校验发起人身份，docs/01 六节）。 */
    public StocktakeSummaryResponse cancel(long stocktakeId, long operatorId) {
        StocktakeEntity st = txTemplate.execute(status -> {
            StocktakeEntity locked = stocktakeMapper.lockById(stocktakeId);
            if (locked == null) {
                throw new BizException(ErrorCode.NOT_FOUND);
            }
            if (locked.getStatus() != STATUS_ACTIVE) {
                throw new BizException(ErrorCode.STOCKTAKE_STATUS_INVALID);
            }
            if (locked.getCreatedBy() == null || locked.getCreatedBy() != operatorId) {
                throw new BizException(ErrorCode.FORBIDDEN);
            }
            locked.setStatus(STATUS_CANCELLED);
            stocktakeMapper.updateById(locked);
            auditRecorder.record("STOCKTAKE_CANCEL", "stocktake", locked.getId(),
                    Map.of("stocktakeNo", locked.getStocktakeNo()));
            return locked;
        });
        sseHub.broadcast(SyncEvent.TYPE_STOCKTAKE, st.getStocktakeNo(), operatorId);
        return summaryOf(st, null, operatorId);
    }

    // ------------------------------------------------------------------ 差异处理

    /**
     * 差异处理：CONFIRM=应用变更+STOCKTAKE_ADJUST 流水（clientReqId 幂等契约同库存
     * 动作，docs/01 7.0——按键读回原结果 200 出清，绝不 409）；IGNORE=仅状态置位
     * （状态幂等：重复 IGNORE 观察到已置位即返回现状）。全处理完自动转已确认。
     */
    public StocktakeDiffActionResponse resolve(long stocktakeId, long diffId, StocktakeDiffActionRequest req,
            long operatorId, String operatorName) {
        boolean confirm = "CONFIRM".equals(req.action());
        Resolved done = null;
        for (int attempt = 0; attempt < 2 && done == null; attempt++) {
            try {
                done = txTemplate.execute(status -> resolveOnce(
                        stocktakeId, diffId, confirm, req.clientReqId(), operatorId, operatorName));
            } catch (DiffTakenMarker e) {
                // 差异已被并发处理：整轮回滚换新事务，重读后按现状给 409010/重放
            }
        }
        if (done == null) {
            done = resolveFinalCheck(stocktakeId, diffId, confirm, req.clientReqId());
        }
        if (done.fresh()) {
            sseHub.broadcast(SyncEvent.TYPE_STOCKTAKE, done.stocktakeNo(), operatorId);
            if (done.itemCode() != null) {
                // CONFIRM 改了商品现态：同时失效库存域（列表/扫码卡重取）
                sseHub.broadcast(SyncEvent.TYPE_INVENTORY, done.itemCode(), operatorId);
            }
        }
        return done.response();
    }

    /**
     * 单轮（事务内）：幂等读回先于一切（成功后的同键重试在单据已转已确认时仍须
     * 200 出清）→ 行锁 → IGNORE 状态幂等（先于单据状态校验：忽略最后一条差异会
     * 把单据转已确认，丢响应后的同键重试必须仍 200）→ 状态校验 → 差异现状分支。
     * item version 冲突返回 null（此刻尚无写入，空提交换新事务重读）；差异条件
     * 置位失败抛 {@link DiffTakenMarker} 回滚整轮（此前已写入商品/流水，必须随轮撤销）。
     */
    private Resolved resolveOnce(long stocktakeId, long diffId, boolean confirm, String clientReqId,
            long operatorId, String operatorName) {
        StockLedgerEntity replayLedger = findReplayedLedger(clientReqId, diffId);
        if (replayLedger != null) {
            return replayOf(replayLedger, stocktakeId, diffId);
        }
        StocktakeEntity st = stocktakeMapper.lockById(stocktakeId);
        if (st == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        StocktakeDiffEntity diff = requireDiff(stocktakeId, diffId);
        if (!confirm && diff.getConfirmStatus() == StocktakeDiffEntity.CONFIRM_IGNORED) {
            // IGNORE 状态幂等：已忽略的重复请求返回现状（状态即原结果）
            return new Resolved(StocktakeDiffRowResponse.from(diff, null), null, false,
                    st.getStocktakeNo(), null);
        }
        if (st.getStatus() != STATUS_PENDING_CONFIRM) {
            throw new BizException(ErrorCode.STOCKTAKE_STATUS_INVALID);
        }
        if (diff.getConfirmStatus() != StocktakeDiffEntity.CONFIRM_PENDING) {
            throw new BizException(ErrorCode.STOCKTAKE_STATUS_INVALID,
                    "この差異は既に処理されています。画面を再読み込みしてください");
        }
        return confirm
                ? confirmOnce(st, diff, clientReqId, operatorId, operatorName)
                : ignoreOnce(st, diff, operatorId);
    }

    /** CONFIRM：边表校验→商品乐观锁更新→STOCKTAKE_ADJUST 流水→差异回链置位→转态复核。 */
    private Resolved confirmOnce(StocktakeEntity st, StocktakeDiffEntity diff, String clientReqId,
            long operatorId, String operatorName) {
        if (diff.getDiffType() == StocktakeDiffEntity.TYPE_FROZEN) {
            throw new BizException(ErrorCode.STOCKTAKE_STATUS_INVALID,
                    "取り消し済み・削除済みの商品は調整できません。無視するか個別に対応してください");
        }
        ItemEntity item = requireActionable(diff.getItemId());
        InventoryAction action = switch (diff.getDiffType()) {
            case StocktakeDiffEntity.TYPE_LOSS -> InventoryAction.STOCKTAKE_LOSS;
            case StocktakeDiffEntity.TYPE_GAIN -> InventoryAction.STOCKTAKE_GAIN;
            default -> InventoryAction.STOCKTAKE_WH;
        };
        InventoryStateMachine.Outcome outcome = InventoryStateMachine.apply(
                action, item.getStockStatus(), item.getSaleStatus());
        boolean movesToStocktakeWh = action != InventoryAction.STOCKTAKE_LOSS;
        LocalDateTime now = LocalDateTime.now(clock);

        // ① 商品乐观锁条件更新（0 行=version 冲突：此刻尚无写入，null 换新事务重读）
        LambdaUpdateWrapper<ItemEntity> wrapper = new LambdaUpdateWrapper<ItemEntity>()
                .eq(ItemEntity::getId, item.getId())
                .eq(ItemEntity::getVersion, item.getVersion())
                .set(ItemEntity::getStockStatus, outcome.stockTo())
                .set(ItemEntity::getSaleStatus, outcome.saleTo())
                .set(ItemEntity::getUpdatedBy, operatorId)
                .set(ItemEntity::getUpdatedAt, now)
                .set(ItemEntity::getVersion, item.getVersion() + 1);
        if (movesToStocktakeWh) {
            wrapper.set(ItemEntity::getWarehouse, st.getWarehouse());
        }
        if (itemMapper.update(null, wrapper) == 0) {
            return null;
        }

        // ② STOCKTAKE_ADJUST 流水（wh 引用确认时刻现仓，对账不变量恒成立）
        StockLedgerEntity ledger = new StockLedgerEntity();
        ledger.setClientReqId(clientReqId);
        ledger.setTxnType(TxnType.STOCKTAKE_ADJUST.id());
        ledger.setItemId(item.getId());
        ledger.setItemCode(item.getItemCode());
        ledger.setStockFrom(item.getStockStatus());
        ledger.setStockTo(outcome.stockTo());
        ledger.setSaleFrom(item.getSaleStatus());
        ledger.setSaleTo(outcome.saleTo());
        ledger.setQtyChange(action == InventoryAction.STOCKTAKE_LOSS ? -1
                : action == InventoryAction.STOCKTAKE_GAIN ? 1 : 0);
        if (action == InventoryAction.STOCKTAKE_LOSS) {
            ledger.setWhFrom(item.getWarehouse());
        } else {
            ledger.setWhTo(st.getWarehouse());
            if (action == InventoryAction.STOCKTAKE_WH) {
                ledger.setWhFrom(item.getWarehouse());
            }
        }
        ledger.setReason("棚卸調整 " + st.getStocktakeNo());
        ledger.setRefType("stocktake_diff");
        ledger.setRefId(diff.getId());
        ledger.setOperatorId(operatorId);
        ledger.setOperatorName(operatorName);
        ledger.setCreatedAt(now);
        ledgerMapper.insert(ledger);

        // ③ 差异条件置位+流水回链（0 行=已被并发处理：回滚本轮全部写入后重读）
        diff.setConfirmStatus(StocktakeDiffEntity.CONFIRM_ADJUSTED);
        diff.setAdjustLedgerId(ledger.getId());
        diff.setConfirmedBy(operatorId);
        diff.setConfirmedAt(now);
        if (diffMapper.update(null, new LambdaUpdateWrapper<StocktakeDiffEntity>()
                .eq(StocktakeDiffEntity::getId, diff.getId())
                .eq(StocktakeDiffEntity::getConfirmStatus, StocktakeDiffEntity.CONFIRM_PENDING)
                .set(StocktakeDiffEntity::getConfirmStatus, StocktakeDiffEntity.CONFIRM_ADJUSTED)
                .set(StocktakeDiffEntity::getAdjustLedgerId, ledger.getId())
                .set(StocktakeDiffEntity::getConfirmedBy, operatorId)
                .set(StocktakeDiffEntity::getConfirmedAt, now)) == 0) {
            throw new DiffTakenMarker();
        }
        auditRecorder.record("STOCKTAKE_DIFF_CONFIRM", "stocktake", st.getId(), Map.of(
                "stocktakeNo", st.getStocktakeNo(),
                "itemCode", item.getItemCode(),
                "diffType", diff.getDiffType(),
                "action", "CONFIRM"));
        flipIfAllResolved(st.getId());
        int finalWarehouse = movesToStocktakeWh ? st.getWarehouse() : item.getWarehouse();
        return new Resolved(
                StocktakeDiffRowResponse.from(diff, null),
                new ActionResult(item.getId(), item.getItemCode(),
                        outcome.stockTo(), outcome.saleTo(), finalWarehouse),
                true, st.getStocktakeNo(), item.getItemCode());
    }

    /** IGNORE：仅差异状态置位（无流水——实物未动不调账）；重复请求按状态幂等返回。 */
    private Resolved ignoreOnce(StocktakeEntity st, StocktakeDiffEntity diff, long operatorId) {
        LocalDateTime now = LocalDateTime.now(clock);
        diff.setConfirmStatus(StocktakeDiffEntity.CONFIRM_IGNORED);
        diff.setConfirmedBy(operatorId);
        diff.setConfirmedAt(now);
        if (diffMapper.update(null, new LambdaUpdateWrapper<StocktakeDiffEntity>()
                .eq(StocktakeDiffEntity::getId, diff.getId())
                .eq(StocktakeDiffEntity::getConfirmStatus, StocktakeDiffEntity.CONFIRM_PENDING)
                .set(StocktakeDiffEntity::getConfirmStatus, StocktakeDiffEntity.CONFIRM_IGNORED)
                .set(StocktakeDiffEntity::getConfirmedBy, operatorId)
                .set(StocktakeDiffEntity::getConfirmedAt, now)) == 0) {
            throw new DiffTakenMarker();
        }
        auditRecorder.record("STOCKTAKE_DIFF_IGNORE", "stocktake", st.getId(), Map.of(
                "stocktakeNo", st.getStocktakeNo(),
                "itemCode", diff.getItemCode(),
                "diffType", diff.getDiffType(),
                "action", "IGNORE"));
        flipIfAllResolved(st.getId());
        return new Resolved(StocktakeDiffRowResponse.from(diff, null), null, true,
                st.getStocktakeNo(), null);
    }

    /** 终局复核（事务外=最新已提交快照）：同键流水在=重放 200；差异已处理=409010；仍待确认=真冲突。 */
    private Resolved resolveFinalCheck(long stocktakeId, long diffId, boolean confirm, String clientReqId) {
        StockLedgerEntity ledger = findReplayedLedger(clientReqId, diffId);
        if (ledger != null) {
            return replayOf(ledger, stocktakeId, diffId);
        }
        StocktakeDiffEntity diff = requireDiff(stocktakeId, diffId);
        if (diff.getConfirmStatus() == StocktakeDiffEntity.CONFIRM_PENDING) {
            throw new BizException(ErrorCode.CONFLICT);
        }
        if (!confirm && diff.getConfirmStatus() == StocktakeDiffEntity.CONFIRM_IGNORED) {
            return new Resolved(StocktakeDiffRowResponse.from(diff, null), null, false, null, null);
        }
        throw new BizException(ErrorCode.STOCKTAKE_STATUS_INVALID,
                "この差異は既に処理されています。画面を再読み込みしてください");
    }

    // ------------------------------------------------------------------ 内部

    /** 幂等读回：按全局唯一键查（uk_client_req）；类型/差异不符=键被挪用→400 防脏读。 */
    private StockLedgerEntity findReplayedLedger(String clientReqId, long diffId) {
        StockLedgerEntity ledger = ledgerMapper.selectOne(new LambdaQueryWrapper<StockLedgerEntity>()
                .eq(StockLedgerEntity::getClientReqId, clientReqId));
        if (ledger == null) {
            return null;
        }
        if (ledger.getTxnType() != TxnType.STOCKTAKE_ADJUST.id()) {
            throw new BizException(ErrorCode.VALIDATION, "冪等キーが他の操作で使用されています");
        }
        if (ledger.getRefId() == null || ledger.getRefId() != diffId) {
            throw new BizException(ErrorCode.VALIDATION, "冪等キーが他の差異で使用されています");
        }
        return ledger;
    }

    /** 重放响应：差异现状+商品现态快照（7.0：重放返回首次结果语义）；归属单校验防跨单读回。 */
    private Resolved replayOf(StockLedgerEntity ledger, long stocktakeId, long diffId) {
        StocktakeDiffEntity diff = requireDiff(stocktakeId, diffId);
        ItemEntity item = itemMapper.selectById(ledger.getItemId());
        if (item == null) {
            throw new BizException(ErrorCode.INTERNAL, "棚卸調整の再現に失敗しました");
        }
        return new Resolved(StocktakeDiffRowResponse.from(diff, null), resultOf(item),
                false, null, null);
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

    private StocktakeEntity requireStocktake(long id) {
        StocktakeEntity st = stocktakeMapper.selectById(id);
        if (st == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return st;
    }

    private StocktakeDiffEntity requireDiff(long stocktakeId, long diffId) {
        StocktakeDiffEntity diff = diffMapper.selectById(diffId);
        if (diff == null || !Objects.equals(diff.getStocktakeId(), stocktakeId)) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return diff;
    }

    private ActionResult resultOf(ItemEntity item) {
        return new ActionResult(item.getId(), item.getItemCode(),
                item.getStockStatus(), item.getSaleStatus(), item.getWarehouse());
    }

    /** 差异行「盘点期间（发起→close）有变动流水」标注：一次批查差异件集合。 */
    private void markChangedDuringStocktake(StocktakeEntity st, List<StocktakeDiffEntity> diffs) {
        if (diffs.isEmpty()) {
            return;
        }
        List<Long> diffItemIds = diffs.stream().map(StocktakeDiffEntity::getItemId).toList();
        Set<Long> changed = ledgerMapper.selectList(new LambdaQueryWrapper<StockLedgerEntity>()
                        .select(StockLedgerEntity::getItemId)
                        .in(StockLedgerEntity::getItemId, diffItemIds)
                        .ge(StockLedgerEntity::getCreatedAt, st.getCreatedAt()))
                .stream()
                .map(StockLedgerEntity::getItemId)
                .collect(Collectors.toSet());
        for (StocktakeDiffEntity diff : diffs) {
            if (changed.contains(diff.getItemId())) {
                diff.setNote(NOTE_CHANGED);
            }
        }
    }

    /** 全处理完自动转已确认（行锁内计数准确；仅待确认态可转）。 */
    private void flipIfAllResolved(long stocktakeId) {
        Long pending = diffMapper.selectCount(new LambdaQueryWrapper<StocktakeDiffEntity>()
                .eq(StocktakeDiffEntity::getStocktakeId, stocktakeId)
                .eq(StocktakeDiffEntity::getConfirmStatus, StocktakeDiffEntity.CONFIRM_PENDING));
        if (pending != null && pending == 0) {
            stocktakeMapper.update(null, new LambdaUpdateWrapper<StocktakeEntity>()
                    .eq(StocktakeEntity::getId, stocktakeId)
                    .eq(StocktakeEntity::getStatus, STATUS_PENDING_CONFIRM)
                    .set(StocktakeEntity::getStatus, STATUS_CONFIRMED));
        }
    }

    private StocktakeDiffEntity baseDiff(long stocktakeId, long itemId, String itemCode) {
        StocktakeDiffEntity diff = new StocktakeDiffEntity();
        diff.setStocktakeId(stocktakeId);
        diff.setItemId(itemId);
        diff.setItemCode(itemCode);
        diff.setConfirmStatus(StocktakeDiffEntity.CONFIRM_PENDING);
        return diff;
    }

    private StocktakeSummaryResponse summaryOf(StocktakeEntity st, Integer pendingDiffCount, long currentUserId) {
        Map<Long, String> names = displayNames(Stream.of(st.getCreatedBy(), st.getClosedBy()));
        return StocktakeSummaryResponse.of(st, pendingDiffCount,
                names.get(st.getCreatedBy()), names.get(st.getClosedBy()), currentUserId);
    }

    /** 批量派生待确认差异数（GROUP BY 下推 DB，一次查整页）。 */
    private Map<Long, Integer> pendingDiffCounts(Collection<Long> stocktakeIds) {
        if (stocktakeIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = diffMapper.selectMaps(new QueryWrapper<StocktakeDiffEntity>()
                .select("stocktake_id", "COUNT(*) AS cnt")
                .in("stocktake_id", stocktakeIds)
                .eq("confirm_status", StocktakeDiffEntity.CONFIRM_PENDING)
                .groupBy("stocktake_id"));
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            counts.put(((Number) row.get("stocktake_id")).longValue(),
                    ((Number) row.get("cnt")).intValue());
        }
        return counts;
    }

    private Map<Long, String> displayNames(Stream<Long> userIds) {
        Set<Long> ids = userIds.filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(SysUserEntity::getId, SysUserEntity::getDisplayName));
    }

    /** 单轮执行结果：fresh=false 为重放/状态幂等命中（不广播）。 */
    private record Resolved(StocktakeDiffRowResponse diffRow, ActionResult result, boolean fresh,
            String stocktakeNo, String itemCode) {
        StocktakeDiffActionResponse response() {
            return new StocktakeDiffActionResponse(diffRow, result);
        }
    }

    private record ScanOutcome(boolean repeated, StocktakeEntity stocktake) {
    }

    /** 差异已被并发处理：回滚本轮（商品/流水已写入须随轮撤销），外层换新事务重读。 */
    private static final class DiffTakenMarker extends RuntimeException {
    }
}
