package com.kcgl.module.log;

import com.kcgl.common.web.ApiResponse;
import com.kcgl.module.log.dto.OperationLogListResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 操作日志查询（GET /api/operation-logs，M5-④）：仅管理员（验收 9
 * 「日志不可删改，管理员可查看」）。只读端点——写路径不存在即不可变契约的 API 面。
 */
@RestController
@RequestMapping("/api")
public class OperationLogController {

    private final OperationLogQueryService queryService;

    public OperationLogController(OperationLogQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/operation-logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<OperationLogListResponse> logs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String operatorName,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(queryService.query(new OperationLogQueryService.OperationLogQuery(
                action, entityType, operatorName, from, to, page, size)));
    }
}
