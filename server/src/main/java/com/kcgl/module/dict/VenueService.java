package com.kcgl.module.dict;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kcgl.common.audit.AuditRecorder;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.dict.dto.VenueCreateRequest;
import com.kcgl.module.dict.dto.VenueResponse;
import com.kcgl.module.dict.dto.VenueUpdateRequest;
import com.kcgl.module.item.ItemEntity;
import com.kcgl.module.item.ItemMapper;
import com.kcgl.module.itemcode.SeqItemCodeEntity;
import com.kcgl.module.itemcode.SeqItemCodeMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 会场字典（docs/01 六节）：创建/编辑（改名/改码）→ 可编辑及以上（现场遇日历外新拍卖会自救）；
 * 停用/删除 → 管理员。删除仅限无商品引用（有引用只可停用，D-166）；改码只影响今后生成的
 * 管理号——已录入商品的 item_code/venue_code 是录入时快照，恒不变（D-165/D-166）。
 */
@Service
public class VenueService {

    private final VenueMapper mapper;
    private final ItemMapper itemMapper;
    private final SeqItemCodeMapper seqItemCodeMapper;
    private final Clock clock;
    private final AuditRecorder auditRecorder;

    public VenueService(VenueMapper mapper, ItemMapper itemMapper,
            SeqItemCodeMapper seqItemCodeMapper, Clock clock, AuditRecorder auditRecorder) {
        this.mapper = mapper;
        this.itemMapper = itemMapper;
        this.seqItemCodeMapper = seqItemCodeMapper;
        this.clock = clock;
        this.auditRecorder = auditRecorder;
    }

    /** enabled 过滤可选（录入页只要可选会场；管理页要看全量含停用）。 */
    public List<VenueResponse> list(Boolean enabled) {
        LambdaQueryWrapper<VenueEntity> wrapper = new LambdaQueryWrapper<VenueEntity>()
                .orderByAsc(VenueEntity::getCode);
        if (enabled != null) {
            wrapper.eq(VenueEntity::getEnabled, enabled ? 1 : 0);
        }
        return mapper.selectList(wrapper).stream().map(VenueResponse::from).toList();
    }

    @Transactional
    public VenueResponse create(VenueCreateRequest req) {
        VenueEntity entity = new VenueEntity();
        entity.setCode(normalizeCode(req.code()));
        entity.setName(req.name());
        entity.setEnabled(1);
        entity.setCreatedAt(LocalDateTime.now(clock));
        entity.setUpdatedAt(LocalDateTime.now(clock));
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.VENUE_EXISTS);
        }
        auditRecorder.record("VENUE_CREATE", "auction_venue", entity.getId(),
                Map.of("code", entity.getCode(), "name", req.name()));
        return VenueResponse.from(entity);
    }

    /** 改名/改码：code 空=不改码。改码不波及历史商品（快照语义），故不设引用门槛。 */
    @Transactional
    public VenueResponse update(Long id, VenueUpdateRequest req) {
        VenueEntity entity = requireVenue(id);
        String newCode = req.code() == null || req.code().isBlank()
                ? entity.getCode() : normalizeCode(req.code());
        Map<String, Object> before = Map.of("code", entity.getCode(), "name", entity.getName());
        entity.setCode(newCode);
        entity.setName(req.name());
        entity.setUpdatedAt(LocalDateTime.now(clock));
        try {
            mapper.updateById(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.VENUE_EXISTS);
        }
        auditRecorder.record("VENUE_UPDATE", "auction_venue", id, Map.of(
                "before", before, "after", Map.of("code", newCode, "name", req.name())));
        return VenueResponse.from(entity);
    }

    @Transactional
    public VenueResponse updateStatus(Long id, boolean enabled) {
        VenueEntity entity = requireVenue(id);
        entity.setEnabled(enabled ? 1 : 0);
        entity.setUpdatedAt(LocalDateTime.now(clock));
        mapper.updateById(entity);
        auditRecorder.record("VENUE_STATUS", "auction_venue", id, Map.of(
                "code", entity.getCode(), "enabled", enabled ? 1 : 0));
        return VenueResponse.from(entity);
    }

    /**
     * 物理删除（D-166）：有商品引用（含作废/软删件——历史仍需解析会场名）即拒绝，引导停用；
     * 无引用则连同该会场的发号计数桶一并删除（桶只在有商品时有意义）。
     */
    @Transactional
    public VenueResponse delete(Long id) {
        VenueEntity entity = requireVenue(id);
        Long refs = itemMapper.selectCount(
                new LambdaQueryWrapper<ItemEntity>().eq(ItemEntity::getVenueId, id));
        if (refs != null && refs > 0) {
            throw new BizException(ErrorCode.VENUE_IN_USE);
        }
        seqItemCodeMapper.delete(
                new LambdaQueryWrapper<SeqItemCodeEntity>().eq(SeqItemCodeEntity::getVenueId, id));
        mapper.deleteById(id);
        auditRecorder.record("VENUE_DELETE", "auction_venue", id,
                Map.of("code", entity.getCode(), "name", entity.getName()));
        return VenueResponse.from(entity);
    }

    /** 会场码统一大写存储（输入大小写不敏感），与全大写的管理号一致。 */
    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private VenueEntity requireVenue(Long id) {
        VenueEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return entity;
    }
}
