package com.kcgl.module.itemcode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kcgl.common.audit.AuditRecorder;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.common.util.CodeNormalizer;
import com.kcgl.module.auth.KcglUserDetails;
import com.kcgl.module.dict.PriceBandService;
import com.kcgl.module.dict.dto.PriceBandResponse;
import com.kcgl.module.dict.VenueEntity;
import com.kcgl.module.dict.VenueMapper;
import com.kcgl.module.inventory.StockLedgerEntity;
import com.kcgl.module.inventory.StockLedgerMapper;
import com.kcgl.module.inventory.TxnType;
import com.kcgl.module.image.ImageEntity;
import com.kcgl.module.image.ImageMapper;
import com.kcgl.module.item.ItemEntity;
import com.kcgl.module.item.ItemMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理号引擎·事务体（docs/01 7.1 唯一定义）。每次调用=一个独立事务，由外层
 * {@link ItemCodeService} 在事务边界之外重试（事务内 catch 重试必然
 * UnexpectedRollbackException——InnoDB 已回滚/Spring 已标 rollback-only）。
 *
 * 事务内顺序：幂等读回 → 锁外前置校验（400/404 不进锁）→ 锁桶（不存在 INSERT IGNORE
 * 后重锁）→ 锁内取号（候选号与已提交行冲突=跳号，计数器同步推进保 cur_seq==MAX(seq_no)
 * 自检不变量）→ 推进计数器 + INSERT item + INSERT CREATE 流水 + 审计。
 */
@Service
public class ItemCodeTxService {

    /** 单前缀流水上限（V1 CHECK seq_no 1-99）。 */
    static final int MAX_SEQ = 99;
    /** 跳号搜索防御上限：正常路径 0 次；连续 500 个号被占=数据异常，宁可失败告警也不静默耗尽。 */
    static final int SKIP_LIMIT = 500;

    private final SeqItemCodeMapper seqMapper;
    private final ItemMapper itemMapper;
    private final StockLedgerMapper ledgerMapper;
    private final ImageMapper imageMapper;
    private final VenueMapper venueMapper;
    private final PriceBandService priceBandService;
    private final AuditRecorder auditRecorder;
    private final ItemCodeProperties properties;
    private final Clock clock;

    public ItemCodeTxService(SeqItemCodeMapper seqMapper, ItemMapper itemMapper,
            StockLedgerMapper ledgerMapper, ImageMapper imageMapper, VenueMapper venueMapper,
            PriceBandService priceBandService,
            AuditRecorder auditRecorder, ItemCodeProperties properties, Clock clock) {
        this.seqMapper = seqMapper;
        this.itemMapper = itemMapper;
        this.ledgerMapper = ledgerMapper;
        this.imageMapper = imageMapper;
        this.venueMapper = venueMapper;
        this.priceBandService = priceBandService;
        this.auditRecorder = auditRecorder;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public ItemEntity allocateAndInsert(CreateItemCommand cmd) {
        ItemEntity replayed = findReplayedCreate(cmd.clientReqId());
        if (replayed != null) {
            return replayed;
        }

        // 作废重录：原件须已作废；请求未携带的可选字段由原件继承（表单外字段不可丢，7.1）
        ItemEntity source = null;
        if (cmd.reEntryOf() != null) {
            source = requireVoidedSource(cmd.reEntryOf());
            cmd = inheritFromSource(cmd, source);
        }

        VenueEntity venue = requireVenue(cmd.venueId());
        PriceBandResponse band = priceBandService.match(cmd.purchasePrice());
        int month = cmd.buyDate().getMonthValue();

        SeqItemCodeEntity bucket = lockOrCreateBucket(cmd.venueId(), month);

        String prefix = bucket.getCurPrefix();
        int seq = bucket.getCurSeq();
        int skips = 0;
        String code;
        while (true) {
            if (seq >= MAX_SEQ) {
                prefix = ItemCodeFormatter.nextPrefix(prefix);
                seq = 1;
            } else {
                seq = seq + 1;
            }
            String candidate = ItemCodeFormatter.format(venue.getCode(),
                    month, prefix, seq, band.code(), properties.withPriceCode());
            if (codeIsFree(candidate)) {
                code = candidate;
                break;
            }
            skips++;
            if (skips > SKIP_LIMIT) {
                throw new BizException(ErrorCode.INTERNAL,
                        "管理号候补が連続して" + SKIP_LIMIT + "件使用済みのため、採番を中止しました");
            }
            audit("ITEM_CODE_SKIP", "item_code", null, Map.of(
                    "code", candidate,
                    "venueId", cmd.venueId(),
                    "bucket", String.valueOf(month),
                    "reason", "uk_item_code_conflict"), cmd);
        }

        bucket.setCurPrefix(prefix);
        bucket.setCurSeq(seq);
        bucket.setUpdatedAt(LocalDateTime.now(clock));
        seqMapper.updateById(bucket);

        ItemEntity item = buildItem(cmd, venue, band, code, prefix, seq, month);
        itemMapper.insert(item);
        ledgerMapper.insert(buildCreateLedger(cmd, item));
        audit("ITEM_CREATE", "item", item.getId(), Map.of(
                "itemCode", code,
                "venueCode", venue.getCode(),
                "buyMonth", month,
                "seqPrefix", prefix,
                "seqNo", seq,
                "priceBand", band.code(),
                "purchasePrice", cmd.purchasePrice(),
                "skips", skips), cmd);
        if (source != null) {
            linkReEntry(source, item, cmd.operatorId());
        }
        // 生成列（total_cost/profit）由 DB 计算，重读回填——响应携带真实成本而非 null
        return itemMapper.selectById(item.getId());
    }

    /** 旧号导入结果：落库商品（生成列已回读）+ 计数器跳变说明（null=未推进）。 */
    public record ImportedCode(ItemEntity item, String counterJumpNote) {
    }

    /**
     * Excel 旧号导入（D-058 C/D）：管理号取自文件而非生成。与 {@link #allocateAndInsert}
     * 共用校验/落库/流水/审计内部件，差异在取号段——不搜候选号，而是校验号与行数据
     * 一致（会场段=会場コード列、月=落札日）后按位置序推进计数器。行级错误
     * 抛 VALIDATION（调用方逐行捕获记错误行，坏行不连坐全批）。
     * 不支持 reEntryOf：Excel 无该列，结构上不可达（linkReEntry 审计仍走会话版）。
     * 价格码不回验（档位快照语义同生成路径，D-001）：号的末位字母按行单价重新匹配
     * 档位后存储，与号内字母分歧时详情页由快照徽标呈现。
     */
    @Transactional
    public ImportedCode insertImportedCode(CreateItemCommand cmd, String itemCode, String rowVenueCode) {
        ItemEntity replayed = findReplayedCreate(cmd.clientReqId());
        if (replayed != null) {
            return new ImportedCode(replayed, null);
        }

        String normalized = CodeNormalizer.normalize(itemCode);
        ItemCodeFormatter.ParsedCode parsed;
        try {
            parsed = ItemCodeFormatter.parse(normalized);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.VALIDATION, e.getMessage());
        }
        String rowCode = CodeNormalizer.normalize(rowVenueCode);
        if (!parsed.venueCode().equals(rowCode)) {
            throw new BizException(ErrorCode.VALIDATION, "管理番号の会場コード（" + parsed.venueCode()
                    + "）と会場コード列（" + rowCode + "）が一致しません");
        }

        VenueEntity venue = requireVenueByCode(parsed.venueCode());
        if (parsed.month() != cmd.buyDate().getMonthValue()) {
            throw new BizException(ErrorCode.VALIDATION,
                    "管理番号の月（" + parsed.month() + "）が落札日と一致しません");
        }
        PriceBandResponse band = priceBandService.match(cmd.purchasePrice());

        SeqItemCodeEntity bucket = lockOrCreateBucket(venue.getId(), parsed.month());
        if (!codeIsFree(normalized)) {
            throw new BizException(ErrorCode.VALIDATION, "管理番号は既に使用されています: " + normalized);
        }
        String transition = advanceCounterForImport(bucket, parsed.prefix(), parsed.seq());
        String jumpNote = transition == null ? null
                : venue.getCode() + "-" + parsed.month() + " " + transition;

        ItemEntity item = buildItem(cmd, venue, band, normalized,
                parsed.prefix(), parsed.seq(), parsed.month());
        itemMapper.insert(item);
        ledgerMapper.insert(buildCreateLedger(cmd, item));
        Map<String, Object> detail = new HashMap<>();
        detail.put("itemCode", normalized);
        detail.put("venueCode", venue.getCode());
        detail.put("buyMonth", parsed.month());
        detail.put("seqPrefix", parsed.prefix());
        detail.put("seqNo", parsed.seq());
        detail.put("priceBand", band.code());
        detail.put("purchasePrice", cmd.purchasePrice());
        detail.put("clientReqId", cmd.clientReqId());
        if (jumpNote != null) {
            detail.put("counterJump", jumpNote);
        }
        audit("ITEM_IMPORT", "item", item.getId(), detail, cmd);
        // 生成列（total_cost/profit）由 DB 计算，重读回填
        return new ImportedCode(itemMapper.selectById(item.getId()), jumpNote);
    }

    /**
     * 预览号（无锁只读，E+）：计数器现值+1 推算，不做存在性检查（预览≠保留——
     * 两人可能同见一个号，先保存者得之，后者保存时引擎落库取新号；前端文案须明示以保存为准）。
     */
    public ItemCodePreviewResponse preview(long venueId, LocalDate buyDate, long price) {
        VenueEntity venue = requireVenue(venueId);
        PriceBandResponse band = priceBandService.match(price);
        int month = buyDate.getMonthValue();
        SeqItemCodeEntity bucket = seqMapper.selectOne(new LambdaQueryWrapper<SeqItemCodeEntity>()
                .eq(SeqItemCodeEntity::getVenueId, venueId)
                .eq(SeqItemCodeEntity::getMonth, month));
        String prefix = "A";
        int seq = 1;
        if (bucket != null) {
            prefix = bucket.getCurPrefix();
            if (bucket.getCurSeq() >= MAX_SEQ) {
                prefix = ItemCodeFormatter.nextPrefix(prefix);
            } else {
                seq = bucket.getCurSeq() + 1;
            }
        }
        String code = ItemCodeFormatter.format(venue.getCode(), month,
                prefix, seq, band.code(), properties.withPriceCode());
        return new ItemCodePreviewResponse(code, band.code(), prefix, seq);
    }

    // ------------------------------------------------------------------ 内部

    /** remark 列宽（V1 DDL item.remark VARCHAR(500)）。 */
    static final int REMARK_MAX = 500;

    /** 重录原件须存在且已作废（软删件按不存在处理；未作废件走 409007 引导先作废）。 */
    private ItemEntity requireVoidedSource(long id) {
        ItemEntity source = itemMapper.selectById(id);
        if (source == null || (source.getDeleted() != null && source.getDeleted() == 1)) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        if (source.getVoided() == null || source.getVoided() != 1) {
            throw new BizException(ErrorCode.ITEM_NOT_VOIDED);
        }
        return source;
    }

    /**
     * 服务端字段继承：请求未携带的可选字段（撮影日/三费用/货架/入库日/组号/备注/扩展五字段）
     * 全部取原件——重录是纠错路径，「携带原商品全部字段」由服务端兜底而非依赖前端表单完备。
     * venueId/buyDate/purchasePrice/warehouse 必填字段不继承（前端预填后用户可改）。
     */
    private CreateItemCommand inheritFromSource(CreateItemCommand cmd, ItemEntity source) {
        String remark = cmd.remark() != null ? cmd.remark() : source.getRemark();
        return new CreateItemCommand(
                cmd.clientReqId(), cmd.reEntryOf(), cmd.venueId(), cmd.buyDate(),
                cmd.photoDate() != null ? cmd.photoDate() : source.getPhotoDate(),
                cmd.purchasePrice(),
                cmd.fee() != null ? cmd.fee() : source.getFee(),
                cmd.shippingFee() != null ? cmd.shippingFee() : source.getShippingFee(),
                cmd.tax() != null ? cmd.tax() : source.getTax(),
                cmd.warehouse(),
                cmd.shelfNo() != null ? cmd.shelfNo() : source.getShelfNo(),
                cmd.warehouseInDate() != null ? cmd.warehouseInDate() : source.getWarehouseInDate(),
                cmd.groupNo() != null ? cmd.groupNo() : source.getGroupNo(),
                appendMarker(remark, "再登録元: " + source.getItemCode()),
                cmd.itemName() != null ? cmd.itemName() : source.getItemName(),
                cmd.category() != null ? cmd.category() : source.getCategory(),
                cmd.authorKiln() != null ? cmd.authorKiln() : source.getAuthorKiln(),
                cmd.sizeText() != null ? cmd.sizeText() : source.getSizeText(),
                cmd.weightG() != null ? cmd.weightG() : source.getWeightG(),
                cmd.salesChannel() != null ? cmd.salesChannel() : source.getSalesChannel(),
                cmd.operatorId(), cmd.operatorName());
    }

    /**
     * 互链落库（同事务）：新件 re_entry_of 已随 INSERT 写入；此处补旧件冗余反链
     * void_re_entry（主链在新件，反链供扫旧码快速定位新号）、remark 双向互写、
     * 图片行复制（同 stored_path/thumb_path 零重传，新 client_uuid——幂等键不复用）。
     */
    private void linkReEntry(ItemEntity source, ItemEntity reEntered, Long operatorId) {
        List<ImageEntity> images = imageMapper.selectList(new LambdaQueryWrapper<ImageEntity>()
                .eq(ImageEntity::getItemId, source.getId())
                .orderByAsc(ImageEntity::getSortOrder));
        LocalDateTime now = LocalDateTime.now(clock);
        for (ImageEntity image : images) {
            ImageEntity copy = new ImageEntity();
            copy.setItemId(reEntered.getId());
            copy.setClientUuid(UUID.randomUUID().toString());
            copy.setStoredPath(image.getStoredPath());
            copy.setThumbPath(image.getThumbPath());
            copy.setImageType(image.getImageType());
            copy.setSortOrder(image.getSortOrder());
            copy.setCreatedBy(operatorId);
            copy.setCreatedAt(now);
            imageMapper.insert(copy);
        }
        source.setVoidReEntry(reEntered.getId());
        source.setRemark(appendMarker(source.getRemark(), "再登録先: " + reEntered.getItemCode()));
        source.setUpdatedBy(operatorId);
        source.setUpdatedAt(now);
        itemMapper.updateById(source);
        auditRecorder.record("ITEM_RE_ENTRY", "item", reEntered.getId(), Map.of(
                "sourceItemId", source.getId(),
                "sourceItemCode", source.getItemCode(),
                "inheritedImages", images.size()));
    }

    /** 互链标记追加；超列宽时标记优先保留（互链是审计链路，比原备注尾巴重要）。 */
    private String appendMarker(String base, String marker) {
        String keep = base == null ? "" : base;
        String joined = keep.isBlank() ? marker : keep + "／" + marker;
        if (joined.length() <= REMARK_MAX) {
            return joined;
        }
        int room = REMARK_MAX - marker.length() - 1;
        return room <= 0 ? marker.substring(0, REMARK_MAX)
                : marker + "／" + keep.substring(0, Math.min(keep.length(), room));
    }

    /**
     * 幂等读回：同 clientReqId 的 CREATE 流水已存在=网络超时重放，返回原商品不再取号。
     * 只认 CREATE 行——键被其他操作类型占用属于前端缺陷，不在读回路径静默兜底
     * （重试三次后 INTERNAL+告警暴露，防静默返回错误数据）。
     */
    private ItemEntity findReplayedCreate(String clientReqId) {
        if (clientReqId == null || clientReqId.isBlank()) {
            return null;
        }
        StockLedgerEntity ledger = ledgerMapper.selectOne(new LambdaQueryWrapper<StockLedgerEntity>()
                .eq(StockLedgerEntity::getClientReqId, clientReqId)
                .eq(StockLedgerEntity::getTxnType, TxnType.CREATE.id()));
        if (ledger == null) {
            return null;
        }
        return itemMapper.selectById(ledger.getItemId());
    }

    /** 会场须存在；停用会场仍允许补录（历史落札合法路径，前端下拉默认只给启用会场）。 */
    private VenueEntity requireVenue(Long venueId) {
        VenueEntity venue = venueMapper.selectById(venueId);
        if (venue == null) {
            throw new BizException(ErrorCode.VENUE_NOT_FOUND);
        }
        return venue;
    }

    /** 按会场码查会场（旧号导入路径：号内段为权威来源）；停用仍允许（补录语义同上）。 */
    private VenueEntity requireVenueByCode(String code) {
        VenueEntity venue = venueMapper.selectOne(
                new LambdaQueryWrapper<VenueEntity>().eq(VenueEntity::getCode, code));
        if (venue == null) {
            throw new BizException(ErrorCode.VENUE_NOT_FOUND);
        }
        return venue;
    }

    private SeqItemCodeEntity lockOrCreateBucket(long venueId, int month) {
        SeqItemCodeEntity bucket = seqMapper.lockBucket(venueId, month);
        if (bucket != null) {
            return bucket;
        }
        try {
            seqMapper.insertIgnoreBucket(venueId, month);
        } catch (DuplicateKeyException e) {
            // 并发建桶竞争：IGNORE 兜住，重锁读胜者
        }
        bucket = seqMapper.lockBucket(venueId, month);
        if (bucket == null) {
            throw new BizException(ErrorCode.INTERNAL, "採番カウンタ行の作成に失敗しました");
        }
        return bucket;
    }

    /**
     * 锁内存在性检查：行锁已串行化本引擎写路径，此处兜的是「已提交但未推进计数器」的
     * 外部行（历史数据修复/导入旁路），跳号推进而非无限重试。
     */
    private boolean codeIsFree(String code) {
        Long count = itemMapper.selectCount(
                new LambdaQueryWrapper<ItemEntity>().eq(ItemEntity::getItemCode, code));
        return count == null || count == 0;
    }

    /**
     * 旧号导入的计数器推进（D-058 D 位置序）：导入号在当前前缀内超前→推进 cur_seq；
     * 前缀位置超前→整桶跳变 cur_prefix/cur_seq；号在计数器后方（历史旧号）→不动——
     * 位置序只进不退，后续生成号永不与已导入号顶撞（uk 之外的前置保证）。前缀大小
     * 用 {@link ItemCodeFormatter#prefixRank}（字符串比较方向错误，见其 javadoc）。
     * 返回位置迁移说明（null=未推进）；调用方装饰桶上下文后入批次 note。
     */
    private String advanceCounterForImport(SeqItemCodeEntity bucket, String prefix, int seq) {
        int curRank = ItemCodeFormatter.prefixRank(bucket.getCurPrefix());
        int importRank = ItemCodeFormatter.prefixRank(prefix);
        if (importRank < curRank) {
            return null;
        }
        if (importRank == curRank && seq <= bucket.getCurSeq()) {
            return null;
        }
        String from = bucket.getCurPrefix() + bucket.getCurSeq();
        bucket.setCurPrefix(prefix);
        bucket.setCurSeq(seq);
        bucket.setUpdatedAt(LocalDateTime.now(clock));
        seqMapper.updateById(bucket);
        return from + "→" + prefix + seq;
    }

    /**
     * 审计操作人解析：请求线程走会话快照（带 ip/ua）；Excel 导入等后台线程无
     * SecurityContext，回落指令携带的操作人（与台账 operator 快照同源，ip/ua 留
     * NULL——同 {@link AuditRecorder} 后台线程版契约，docs/01 5.3）。
     */
    private void audit(String action, String entityType, Long entityId,
            Map<String, Object> detail, CreateItemCommand cmd) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof KcglUserDetails) {
            auditRecorder.record(action, entityType, entityId, detail);
            return;
        }
        auditRecorder.record(action, entityType, entityId, detail,
                cmd.operatorId(), cmd.operatorName());
    }

    private ItemEntity buildItem(CreateItemCommand cmd, VenueEntity venue,
            PriceBandResponse band, String code, String prefix, int seq, int month) {
        ItemEntity item = new ItemEntity();
        item.setItemCode(code);
        item.setVenueId(venue.getId());
        item.setVenueCode(venue.getCode());
        item.setBuyMonth(month);
        item.setSeqPrefix(prefix);
        item.setSeqNo(seq);
        item.setBuyDate(cmd.buyDate());
        item.setPhotoDate(cmd.photoDate());
        item.setPurchasePrice(cmd.purchasePrice());
        item.setFee(cmd.fee());
        item.setShippingFee(cmd.shippingFee());
        item.setTax(cmd.tax());
        item.setPriceBandCode(band.code());
        item.setWarehouse(cmd.warehouse());
        item.setShelfNo(cmd.shelfNo());
        item.setWarehouseInDate(cmd.warehouseInDate());
        item.setGroupNo(cmd.groupNo());
        item.setRemark(cmd.remark());
        item.setItemName(cmd.itemName());
        item.setCategory(cmd.category());
        item.setAuthorKiln(cmd.authorKiln());
        item.setSizeText(cmd.sizeText());
        item.setWeightG(cmd.weightG());
        item.setSalesChannel(cmd.salesChannel());
        item.setReEntryOf(cmd.reEntryOf());
        item.setStockStatus(0);
        item.setSaleStatus(0);
        item.setVoided(0);
        item.setDeleted(0);
        item.setCreatedBy(cmd.operatorId());
        item.setCreatedAt(LocalDateTime.now(clock));
        item.setVersion(0);
        return item;
    }

    /** CREATE 行：进入在途态（stock_to=0），不占仓账（wh_from/to=NULL），件数 0。 */
    private StockLedgerEntity buildCreateLedger(CreateItemCommand cmd, ItemEntity item) {
        StockLedgerEntity ledger = new StockLedgerEntity();
        ledger.setClientReqId(cmd.clientReqId());
        ledger.setTxnType(TxnType.CREATE.id());
        ledger.setItemId(item.getId());
        ledger.setItemCode(item.getItemCode());
        ledger.setStockTo(0);
        ledger.setQtyChange(0);
        ledger.setOperatorId(cmd.operatorId());
        ledger.setOperatorName(cmd.operatorName());
        ledger.setCreatedAt(LocalDateTime.now(clock));
        return ledger;
    }
}
