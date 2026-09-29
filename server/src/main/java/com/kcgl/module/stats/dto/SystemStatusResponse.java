package com.kcgl.module.stats.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * システム状況（GET /api/stats/system，M5-④，管理员专用，docs/01 7.9/9.3）：
 * 甲方远程排障速览。全部只读白名单字段——不含路径明细/密钥/请求体；
 * 阈值判断不在本响应内（水位语义由前端对照阈值呈现，自检判定归 SelfCheckService）。
 */
public record SystemStatusResponse(
        String appVersion,
        String flywayVersion,
        LocalDateTime startedAt,
        long uptimeSeconds,
        Heap heap,
        Pool pool,
        int sseConnections,
        Disk disk,
        Volumes volumes,
        CodeEngine codeEngine,
        ExcelBatches excelBatches,
        List<DayCount> clientErrors7d,
        long openAlerts) {

    /** JVM 堆（字节）。 */
    public record Heap(long usedBytes, long maxBytes) {
    }

    /** HikariCP 连接池快照（池未就绪时各值为 -1）。 */
    public record Pool(int active, int idle, int total, int waiting) {
    }

    /** 图片卷（唯一可控增长卷）水位；目录不存在=全零（不可用，非故障）。 */
    public record Disk(String path, long totalBytes, long usableBytes, int usedPercent) {
    }

    /** 核心表数据量。 */
    public record Volumes(long items, long ledgers, long operationLogs, long clientErrors) {
    }

    /** 号引擎吞吐（operation_log 口径：发号与跳号留痕计数）。 */
    public record CodeEngine(long issued, long skipped) {
    }

    /** Excel 批次近况 + 最近 5 批摘要。 */
    public record ExcelBatches(long total, long done, long failed, long processing,
            List<RecentBatch> recent) {

        public record RecentBatch(long id, String originalFilename, int status,
                int rowCount, int errorCount, LocalDateTime createdAt, LocalDateTime finishedAt) {
        }
    }

    /** client_error 近 7 日按日计数（旧在前）。 */
    public record DayCount(String date, long count) {
    }
}
