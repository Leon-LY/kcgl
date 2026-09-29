package com.kcgl.module.log;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kcgl.module.log.dto.OperationLogListResponse;
import org.springframework.stereotype.Service;

/**
 * 操作日志查询（M5-④）：只读（验收 9「日志不可删改」——无任何写路径，
 * DB 账号仅 SELECT,INSERT 双保险）。action 前缀筛选（UI 输入框明示
 * 「前方一致」，与台帳 itemCode 同语义——E2E 实证拦截过 eq 误配）；
 * entityType 封闭小集合走等值；operatorName 模糊；排序恒 id 倒序。
 */
@Service
public class OperationLogQueryService {

    static final int MAX_PAGE_SIZE = 100;

    private final OperationLogMapper logMapper;

    public OperationLogQueryService(OperationLogMapper logMapper) {
        this.logMapper = logMapper;
    }

    public OperationLogListResponse query(OperationLogQuery query) {
        int safePage = Math.max(query.page(), 1);
        int safeSize = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
        LambdaQueryWrapper<OperationLogEntity> wrapper = new LambdaQueryWrapper<OperationLogEntity>()
                .likeRight(query.action() != null && !query.action().isBlank(),
                        OperationLogEntity::getAction, query.action())
                .eq(query.entityType() != null && !query.entityType().isBlank(),
                        OperationLogEntity::getEntityType, query.entityType())
                .like(query.operatorName() != null && !query.operatorName().isBlank(),
                        OperationLogEntity::getOperatorName, query.operatorName())
                .ge(query.from() != null, OperationLogEntity::getCreatedAt, query.from())
                .lt(query.to() != null, OperationLogEntity::getCreatedAt, query.to())
                .orderByDesc(OperationLogEntity::getId);
        Page<OperationLogEntity> result = logMapper.selectPage(Page.of(safePage, safeSize), wrapper);
        return new OperationLogListResponse(result.getTotal(), safePage, safeSize,
                result.getRecords().stream().map(OperationLogQueryService::row).toList());
    }

    private static OperationLogListResponse.Row row(OperationLogEntity log) {
        return new OperationLogListResponse.Row(
                log.getId(),
                log.getAction(),
                log.getEntityType(),
                log.getEntityId(),
                log.getDetail(),
                log.getOperatorName(),
                log.getIp(),
                log.getUa(),
                log.getCreatedAt());
    }

    /** 查询参数（全部可选）；时间区间 [from, to) 闭开。 */
    public record OperationLogQuery(
            String action,
            String entityType,
            String operatorName,
            java.time.LocalDateTime from,
            java.time.LocalDateTime to,
            int page,
            int size) {
    }
}
