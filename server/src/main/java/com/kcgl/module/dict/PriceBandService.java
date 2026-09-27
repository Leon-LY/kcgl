package com.kcgl.module.dict;

import com.kcgl.common.audit.AuditRecorder;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.dict.dto.PriceBandResponse;
import com.kcgl.module.dict.dto.PriceBandUpsertRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 价格档位（docs/01 5.3/六节）：左闭右开区间，NULL=无界端；区间重叠校验在
 * lockAll() 行锁内做（全表 ≤26 行，锁全表代价可忽略）——两笔并发建档位
 * 各自读到对方未提交的区间前，行锁先保证串行。停用只影响 match 可达性，
 * 不放松重叠校验（跨全部行校验：重新启用不可能凭空造出重叠态）。
 */
@Service
public class PriceBandService {

    private final PriceBandMapper mapper;
    private final Clock clock;
    private final AuditRecorder auditRecorder;

    public PriceBandService(PriceBandMapper mapper, Clock clock, AuditRecorder auditRecorder) {
        this.mapper = mapper;
        this.clock = clock;
        this.auditRecorder = auditRecorder;
    }

    /** 列表按 lower_bound 升序（NULL 首位=无下限首档），管理页与录入页共用。 */
    public List<PriceBandResponse> list() {
        return mapper.selectList(null).stream()
                .sorted((a, b) -> {
                    if (a.getLowerBound() == null) { return b.getLowerBound() == null ? 0 : -1; }
                    if (b.getLowerBound() == null) { return 1; }
                    return Long.compare(a.getLowerBound(), b.getLowerBound());
                })
                .map(PriceBandResponse::from)
                .toList();
    }

    /** 档位匹配（左闭右开）：无命中=价格落在区间空档或档位全停用 → 404002 引导后台配置。 */
    public PriceBandResponse match(long price) {
        for (PriceBandResponse band : list()) {
            if (band.enabled() && contains(band, price)) {
                return band;
            }
        }
        throw new BizException(ErrorCode.PRICE_BAND_NOT_MATCHED);
    }

    @Transactional
    public PriceBandResponse create(PriceBandUpsertRequest req) {
        validateRange(req);
        List<PriceBandEntity> all = mapper.lockAll();
        requireCodeFree(all, req.code(), null);
        requireNoOverlap(all, req.code(), req.lowerBound(), req.upperBound(), null);
        PriceBandEntity entity = insertBand(req);
        auditRecorder.record("BAND_CREATE", "price_band", entity.getId(), Map.of(
                "code", req.code(), "lower", nullSafe(req.lowerBound()), "upper", nullSafe(req.upperBound())));
        return PriceBandResponse.from(entity);
    }

    @Transactional
    public PriceBandResponse update(Long id, PriceBandUpsertRequest req) {
        validateRange(req);
        if (mapper.selectById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        List<PriceBandEntity> all = mapper.lockAll();
        requireCodeFree(all, req.code(), id);
        requireNoOverlap(all, req.code(), req.lowerBound(), req.upperBound(), id);
        PriceBandEntity entity = new PriceBandEntity();
        entity.setId(id);
        entity.setCode(req.code());
        entity.setLowerBound(req.lowerBound());
        entity.setUpperBound(req.upperBound());
        entity.setUpdatedAt(LocalDateTime.now(clock));
        mapper.updateById(entity);
        auditRecorder.record("BAND_UPDATE", "price_band", id, Map.of(
                "code", req.code(), "lower", nullSafe(req.lowerBound()), "upper", nullSafe(req.upperBound())));
        return PriceBandResponse.from(mapper.selectById(id));
    }

    @Transactional
    public PriceBandResponse updateStatus(Long id, boolean enabled) {
        PriceBandEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        entity.setEnabled(enabled ? 1 : 0);
        entity.setUpdatedAt(LocalDateTime.now(clock));
        mapper.updateById(entity);
        auditRecorder.record("BAND_STATUS", "price_band", id, Map.of(
                "code", entity.getCode(), "enabled", enabled ? 1 : 0));
        return PriceBandResponse.from(entity);
    }

    // ------------------------------------------------------------------ 内部

    private static void validateRange(PriceBandUpsertRequest req) {
        if (req.isRangeInvalid()) {
            throw new BizException(ErrorCode.VALIDATION, "下限は上限より小さくしてください");
        }
    }

    private PriceBandEntity insertBand(PriceBandUpsertRequest req) {
        PriceBandEntity entity = new PriceBandEntity();
        entity.setCode(req.code());
        entity.setLowerBound(req.lowerBound());
        entity.setUpperBound(req.upperBound());
        entity.setEnabled(1);
        entity.setCreatedAt(LocalDateTime.now(clock));
        entity.setUpdatedAt(LocalDateTime.now(clock));
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 预检与插入之间理论上的竞态兜底（uk_code）
            throw new BizException(ErrorCode.PRICE_BAND_EXISTS);
        }
        return entity;
    }

    private void requireCodeFree(List<PriceBandEntity> all, String code, Long excludeId) {
        boolean taken = all.stream().anyMatch(b -> b.getCode().equals(code) && !b.getId().equals(excludeId));
        if (taken) {
            throw new BizException(ErrorCode.PRICE_BAND_EXISTS);
        }
    }

    /** 严格区间相交判定（NULL 视为 ±∞）；相邻区间（上界==下界）不算重叠。 */
    private void requireNoOverlap(List<PriceBandEntity> all, String code, Long lower, Long upper, Long excludeId) {
        for (PriceBandEntity band : all) {
            if (band.getId().equals(excludeId)) {
                continue;
            }
            long l1 = band.getLowerBound() == null ? Long.MIN_VALUE : band.getLowerBound();
            long u1 = band.getUpperBound() == null ? Long.MAX_VALUE : band.getUpperBound();
            long l2 = lower == null ? Long.MIN_VALUE : lower;
            long u2 = upper == null ? Long.MAX_VALUE : upper;
            if (l1 < u2 && l2 < u1) {
                throw new BizException(ErrorCode.PRICE_BAND_OVERLAP);
            }
        }
    }

    private static boolean contains(PriceBandResponse band, long price) {
        if (band.lowerBound() != null && price < band.lowerBound()) {
            return false;
        }
        return band.upperBound() == null || price < band.upperBound();
    }

    private static String nullSafe(Long value) {
        return value == null ? "unbounded" : String.valueOf(value);
    }
}
