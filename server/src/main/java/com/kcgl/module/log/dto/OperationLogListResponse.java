package com.kcgl.module.log.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作日志查询（GET /api/operation-logs，M5-④）：管理员专用（docs/01 7.9）。
 * id 倒序；detail 为原始 JSON 文本原样下发（前端格式化展示，不改写留痕）。
 */
public record OperationLogListResponse(
        long total,
        int page,
        int size,
        List<Row> rows) {

    public record Row(
            Long id,
            String action,
            String entityType,
            Long entityId,
            String detail,
            String operatorName,
            String ip,
            String ua,
            LocalDateTime createdAt) {
    }
}
