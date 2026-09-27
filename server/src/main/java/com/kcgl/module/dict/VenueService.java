package com.kcgl.module.dict;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kcgl.common.audit.AuditRecorder;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.dict.dto.VenueCreateRequest;
import com.kcgl.module.dict.dto.VenueRenameRequest;
import com.kcgl.module.dict.dto.VenueResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 会场字典（docs/01 六节）：创建/改名 → 可编辑及以上（现场遇日历外新拍卖会自救，六轮 M3）；
 * 停用 → 管理员。只停用不物理删——item.venue_code 是录入时快照，行恒存保快照可解析。
 */
@Service
public class VenueService {

    private final VenueMapper mapper;
    private final Clock clock;
    private final AuditRecorder auditRecorder;

    public VenueService(VenueMapper mapper, Clock clock, AuditRecorder auditRecorder) {
        this.mapper = mapper;
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
        entity.setCode(req.code());
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
                Map.of("code", req.code(), "name", req.name()));
        return VenueResponse.from(entity);
    }

    @Transactional
    public VenueResponse rename(Long id, VenueRenameRequest req) {
        VenueEntity entity = requireVenue(id);
        Map<String, Object> before = Map.of("name", entity.getName());
        entity.setName(req.name());
        entity.setUpdatedAt(LocalDateTime.now(clock));
        mapper.updateById(entity);
        auditRecorder.record("VENUE_RENAME", "auction_venue", id, Map.of(
                "code", entity.getCode(), "before", before, "after", Map.of("name", req.name())));
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

    private VenueEntity requireVenue(Long id) {
        VenueEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return entity;
    }
}
