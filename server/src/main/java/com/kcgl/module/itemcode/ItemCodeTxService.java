package com.kcgl.module.itemcode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kcgl.common.audit.AuditRecorder;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.dict.PriceBandService;
import com.kcgl.module.dict.dto.PriceBandResponse;
import com.kcgl.module.dict.VenueEntity;
import com.kcgl.module.dict.VenueMapper;
import com.kcgl.module.dict.YearCodeEntity;
import com.kcgl.module.dict.YearCodeMapper;
import com.kcgl.module.inventory.StockLedgerEntity;
import com.kcgl.module.inventory.StockLedgerMapper;
import com.kcgl.module.inventory.TxnType;
import com.kcgl.module.item.ItemEntity;
import com.kcgl.module.item.ItemMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

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
    private final VenueMapper venueMapper;
    private final YearCodeMapper yearCodeMapper;
    private final PriceBandService priceBandService;
    private final AuditRecorder auditRecorder;
    private final ItemCodeProperties properties;
    private final Clock clock;

    public ItemCodeTxService(SeqItemCodeMapper seqMapper, ItemMapper itemMapper,
            StockLedgerMapper ledgerMapper, VenueMapper venueMapper, YearCodeMapper yearCodeMapper,
            PriceBandService priceBandService, AuditRecorder auditRecorder,
            ItemCodeProperties properties, Clock clock) {
        this.seqMapper = seqMapper;
        this.itemMapper = itemMapper;
        this.ledgerMapper = ledgerMapper;
        this.venueMapper = venueMapper;
        this.yearCodeMapper = yearCodeMapper;
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

        VenueEntity venue = requireVenue(cmd.venueId());
        YearCodeEntity yearCode = requireYearCode(cmd.buyDate().getYear());
        PriceBandResponse band = priceBandService.match(cmd.purchasePrice());
        int month = cmd.buyDate().getMonthValue();

        SeqItemCodeEntity bucket = lockOrCreateBucket(cmd.venueId(), cmd.buyDate().getYear(), month);

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
            String candidate = ItemCodeFormatter.format(venue.getCode(), yearCode.getCode(),
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
            auditRecorder.record("ITEM_CODE_SKIP", "item_code", null, Map.of(
                    "code", candidate,
                    "venueId", cmd.venueId(),
                    "bucket", cmd.buyDate().getYear() + "-" + month,
                    "reason", "uk_item_code_conflict"));
        }

        bucket.setCurPrefix(prefix);
        bucket.setCurSeq(seq);
        bucket.setUpdatedAt(LocalDateTime.now(clock));
        seqMapper.updateById(bucket);

        ItemEntity item = buildItem(cmd, venue, yearCode, band, code, prefix, seq, month);
        itemMapper.insert(item);
        ledgerMapper.insert(buildCreateLedger(cmd, item));
        auditRecorder.record("ITEM_CREATE", "item", item.getId(), Map.of(
                "itemCode", code,
                "venueCode", venue.getCode(),
                "year", cmd.buyDate().getYear(),
                "buyMonth", month,
                "seqPrefix", prefix,
                "seqNo", seq,
                "priceBand", band.code(),
                "purchasePrice", cmd.purchasePrice(),
                "skips", skips));
        // 生成列（total_cost/profit）由 DB 计算，重读回填——响应携带真实成本而非 null
        return itemMapper.selectById(item.getId());
    }

    /**
     * 预览号（无锁只读，E+）：计数器现值+1 推算，不做存在性检查（预览≠保留——
     * 两人可能同见一个号，先保存者得之，后者保存时引擎落库取新号；前端文案须明示以保存为准）。
     */
    public ItemCodePreviewResponse preview(long venueId, LocalDate buyDate, long price) {
        VenueEntity venue = requireVenue(venueId);
        YearCodeEntity yearCode = requireYearCode(buyDate.getYear());
        PriceBandResponse band = priceBandService.match(price);
        int month = buyDate.getMonthValue();
        SeqItemCodeEntity bucket = seqMapper.selectOne(new LambdaQueryWrapper<SeqItemCodeEntity>()
                .eq(SeqItemCodeEntity::getVenueId, venueId)
                .eq(SeqItemCodeEntity::getYear, buyDate.getYear())
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
        String code = ItemCodeFormatter.format(venue.getCode(), yearCode.getCode(), month,
                prefix, seq, band.code(), properties.withPriceCode());
        return new ItemCodePreviewResponse(code, band.code(), prefix, seq);
    }

    // ------------------------------------------------------------------ 内部

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

    private YearCodeEntity requireYearCode(int year) {
        YearCodeEntity yearCode = yearCodeMapper.selectOne(
                new LambdaQueryWrapper<YearCodeEntity>().eq(YearCodeEntity::getYear, year));
        if (yearCode == null) {
            throw new BizException(ErrorCode.YEAR_CODE_NOT_FOUND);
        }
        return yearCode;
    }

    private SeqItemCodeEntity lockOrCreateBucket(long venueId, int year, int month) {
        SeqItemCodeEntity bucket = seqMapper.lockBucket(venueId, year, month);
        if (bucket != null) {
            return bucket;
        }
        try {
            seqMapper.insertIgnoreBucket(venueId, year, month);
        } catch (DuplicateKeyException e) {
            // 并发建桶竞争：IGNORE 兜住，重锁读胜者
        }
        bucket = seqMapper.lockBucket(venueId, year, month);
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

    private ItemEntity buildItem(CreateItemCommand cmd, VenueEntity venue, YearCodeEntity yearCode,
            PriceBandResponse band, String code, String prefix, int seq, int month) {
        ItemEntity item = new ItemEntity();
        item.setItemCode(code);
        item.setVenueId(venue.getId());
        item.setVenueCode(venue.getCode());
        item.setYear(cmd.buyDate().getYear());
        item.setYearCode(yearCode.getCode());
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
