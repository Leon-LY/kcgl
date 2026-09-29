package com.kcgl.common.obs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kcgl.module.log.ClientErrorEntity;
import com.kcgl.module.log.ClientErrorMapper;
import com.kcgl.module.stats.SystemStatusService;
import com.kcgl.module.stats.dto.SystemStatusResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 诊断导出组装（M5-④）：システム状況快照 + 告警近 30 + client_error 样本 +
 * 环形缓冲 ERROR/WARN。全只读；文件由控制器落 Content-Disposition 附件头。
 */
@Service
public class DiagnosticsExportService {

    private static final int ALERT_LIMIT = 30;
    private static final int CLIENT_ERROR_SAMPLES = 20;

    private final SystemStatusService systemStatusService;
    private final SysAlertMapper alertMapper;
    private final ClientErrorMapper clientErrorMapper;

    public DiagnosticsExportService(SystemStatusService systemStatusService,
            SysAlertMapper alertMapper, ClientErrorMapper clientErrorMapper) {
        this.systemStatusService = systemStatusService;
        this.alertMapper = alertMapper;
        this.clientErrorMapper = clientErrorMapper;
    }

    public DiagnosticsExport build() {
        SystemStatusResponse status = systemStatusService.status();
        List<SysAlertEntity> alerts = alertMapper.selectPage(
                Page.of(1, ALERT_LIMIT),
                new LambdaQueryWrapper<SysAlertEntity>().orderByDesc(SysAlertEntity::getId))
                .getRecords();
        long totalErrors = clientErrorMapper.selectCount(null);
        List<ClientErrorEntity> samples = clientErrorMapper.selectPage(
                Page.of(1, CLIENT_ERROR_SAMPLES),
                new LambdaQueryWrapper<ClientErrorEntity>().orderByDesc(ClientErrorEntity::getId))
                .getRecords();
        return new DiagnosticsExport(
                LocalDateTime.now(),
                status.appVersion(),
                status.flywayVersion(),
                status.uptimeSeconds(),
                status.heap(),
                status.pool(),
                status.sseConnections(),
                status.disk(),
                status.volumes(),
                status.codeEngine(),
                status.excelBatches(),
                status.openAlerts(),
                alerts.stream().map(a -> new DiagnosticsExport.AlertRow(
                        a.getId(), a.getType(), a.getLevel(), a.getMessage(),
                        a.getStatus(), a.getCreatedAt()))
                        .toList(),
                new DiagnosticsExport.ClientErrors(
                        totalErrors,
                        status.clientErrors7d(),
                        samples.stream().map(s -> new DiagnosticsExport.ClientErrors.SampleRow(
                                s.getId(), s.getMessage(), s.getErrorId(), s.getRoute(), s.getCreatedAt()))
                                .toList()),
                RingBufferLogAppender.snapshot());
    }
}
