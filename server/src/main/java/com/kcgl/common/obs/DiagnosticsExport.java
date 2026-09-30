package com.kcgl.common.obs;

import com.kcgl.module.stats.dto.SystemStatusResponse;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 诊断信息一键导出（GET /api/diagnostics/export，M5-④，docs/01 9.3）：
 * 白名单字段（版本/Flyway/uptime/池/磁盘/水位计数/告警近 30/环形缓冲日志/
 * client_error 统计与样本/Excel 批次近况）。backup（M7 接入）=直近备份
 * 状态快照，null=不可知（未配置/无状态文件），与系统状况页同源。
 */
public record DiagnosticsExport(
        LocalDateTime generatedAt,
        String appVersion,
        String flywayVersion,
        long uptimeSeconds,
        SystemStatusResponse.Heap heap,
        SystemStatusResponse.Pool pool,
        int sseConnections,
        SystemStatusResponse.Disk disk,
        SystemStatusResponse.Volumes volumes,
        SystemStatusResponse.CodeEngine codeEngine,
        SystemStatusResponse.ExcelBatches excelBatches,
        long openAlerts,
        SystemStatusResponse.BackupStatus backup,
        List<AlertRow> alerts,
        ClientErrors clientErrors,
        List<String> ringBufferLogs) {

    /** sys_alert 近 30 行（payload 不随包导出——可能含样本管理号列表，按需管理员页看）。 */
    public record AlertRow(
            Long id,
            String type,
            Integer level,
            String message,
            Integer status,
            LocalDateTime createdAt) {
    }

    /** client_error 统计（总量+近 7 日+最近样本 20 行）。 */
    public record ClientErrors(
            long total,
            List<SystemStatusResponse.DayCount> last7d,
            List<SampleRow> recent) {

        public record SampleRow(
                Long id,
                String message,
                String errorId,
                String route,
                LocalDateTime createdAt) {
        }
    }
}
