package com.kcgl.module.dict;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kcgl.common.audit.AuditRecorder;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.dict.dto.YearCodeResponse;
import com.kcgl.module.dict.dto.YearCodeUpsertRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 年代号对照（docs/01 5.3）：V1 种子 2016=A…2041=Z；增/改 → 管理员，
 * 供 2041 之后扩展（Z 用尽规则待 A2 口径确认，增改接口先行就位）。
 */
@Service
public class YearCodeService {

    private final YearCodeMapper mapper;
    private final AuditRecorder auditRecorder;

    public YearCodeService(YearCodeMapper mapper, AuditRecorder auditRecorder) {
        this.mapper = mapper;
        this.auditRecorder = auditRecorder;
    }

    public List<YearCodeResponse> list() {
        return mapper.selectList(new LambdaQueryWrapper<YearCodeEntity>()
                        .orderByAsc(YearCodeEntity::getYear))
                .stream().map(YearCodeResponse::from).toList();
    }

    @Transactional
    public YearCodeResponse create(YearCodeUpsertRequest req) {
        YearCodeEntity entity = new YearCodeEntity();
        entity.setYear(req.year());
        entity.setCode(req.code());
        entity.setCreatedAt(LocalDateTime.now());
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // uk_year 与 uk_code 双唯一，任一撞键都归并为同一提示
            throw new BizException(ErrorCode.YEAR_CODE_EXISTS);
        }
        auditRecorder.record("YEARCODE_CREATE", "year_code", entity.getId(),
                Map.of("year", req.year(), "code", req.code()));
        return YearCodeResponse.from(entity);
    }

    @Transactional
    public YearCodeResponse update(Long id, YearCodeUpsertRequest req) {
        YearCodeEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        Map<String, Object> before = Map.of("year", entity.getYear(), "code", entity.getCode());
        entity.setYear(req.year());
        entity.setCode(req.code());
        try {
            mapper.updateById(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.YEAR_CODE_EXISTS);
        }
        auditRecorder.record("YEARCODE_UPDATE", "year_code", id, Map.of(
                "before", before, "after", Map.of("year", req.year(), "code", req.code())));
        return YearCodeResponse.from(entity);
    }
}
