package com.kcgl.module.stats;

import com.kcgl.KcglApplication;
import com.kcgl.common.sse.SseHub;
import com.kcgl.module.excel.ExcelImportBatchEntity;
import com.kcgl.module.image.ImageProperties;
import com.kcgl.module.stats.dto.SystemStatusResponse;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * システム状況组装（M5-④）：全只读白名单字段。口径来源——
 * JVM/Hikari 经 MXBean 现场；Flyway 取 installed_rank 最新成功行
 * （字符串 MAX 会 "10"&lt;"9" 误序）；数据量/号引擎/批次/告警走 SQL 直查；
 * 磁盘复用自检口径（图片卷，目录不存在=全零不视为故障）。
 */
@Service
public class SystemStatusService {

    private static final int RECENT_BATCHES = 5;
    /** 近 7 日窗口含今天（JST 日界）。 */
    private static final int CLIENT_ERROR_DAYS = 7;

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final SseHub sseHub;
    private final ImageProperties imageProperties;
    private final Clock clock;

    public SystemStatusService(JdbcTemplate jdbcTemplate, DataSource dataSource, SseHub sseHub,
            ImageProperties imageProperties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.sseHub = sseHub;
        this.imageProperties = imageProperties;
        this.clock = clock;
    }

    public SystemStatusResponse status() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long startTime = ManagementFactory.getRuntimeMXBean().getStartTime();
        LocalDateTime now = LocalDateTime.now(clock);
        return new SystemStatusResponse(
                appVersion(),
                flywayVersion(),
                LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(startTime), clock.getZone()),
                Math.max(0, (now.atZone(clock.getZone()).toInstant().toEpochMilli() - startTime) / 1000),
                new SystemStatusResponse.Heap(heap.getUsed(), heap.getMax()),
                pool(),
                sseHub.connectionCount(),
                disk(),
                volumes(),
                codeEngine(),
                excelBatches(),
                clientErrorsPerDay(),
                count("sys_alert", "status = 0"));
    }

    /** jar 清单版本（Boot repackage 写入）；本地未打包运行为 null → dev。 */
    private static String appVersion() {
        String version = KcglApplication.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "dev" : version;
    }

    private String flywayVersion() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 "
                        + "ORDER BY installed_rank DESC LIMIT 1", String.class);
        return versions.isEmpty() ? null : versions.get(0);
    }

    private SystemStatusResponse.Pool pool() {
        if (!(dataSource instanceof HikariDataSource hikari)) {
            return new SystemStatusResponse.Pool(-1, -1, -1, -1);
        }
        HikariPoolMXBean mx = hikari.getHikariPoolMXBean();
        if (mx == null) {
            return new SystemStatusResponse.Pool(-1, -1, -1, -1);
        }
        return new SystemStatusResponse.Pool(
                mx.getActiveConnections(), mx.getIdleConnections(),
                mx.getTotalConnections(), mx.getThreadsAwaitingConnection());
    }

    /** 图片卷水位（与自检同口径：目录不存在=全零）。 */
    private SystemStatusResponse.Disk disk() {
        Path root = Path.of(imageProperties.dir());
        long total = root.toFile().getTotalSpace();
        if (!Files.isDirectory(root) || total <= 0) {
            return new SystemStatusResponse.Disk(imageProperties.dir(), 0, 0, 0);
        }
        long usable = root.toFile().getUsableSpace();
        return new SystemStatusResponse.Disk(imageProperties.dir(), total, usable,
                (int) Math.round((total - usable) * 100.0 / total));
    }

    private SystemStatusResponse.Volumes volumes() {
        return new SystemStatusResponse.Volumes(
                count("item", null), count("stock_ledger", null),
                count("operation_log", null), count("client_error", null));
    }

    private SystemStatusResponse.CodeEngine codeEngine() {
        return new SystemStatusResponse.CodeEngine(
                count("operation_log", "action = 'ITEM_CODE'"),
                count("operation_log", "action = 'ITEM_CODE_SKIP'"));
    }

    private SystemStatusResponse.ExcelBatches excelBatches() {
        List<ExcelImportBatchEntity> recent = jdbcTemplate.query(
                "SELECT * FROM excel_import_batch ORDER BY id DESC LIMIT " + RECENT_BATCHES,
                (rs, n) -> {
                    ExcelImportBatchEntity row = new ExcelImportBatchEntity();
                    row.setId(rs.getLong("id"));
                    row.setOriginalFilename(rs.getString("original_filename"));
                    row.setStatus(rs.getInt("status"));
                    // INT UNSIGNED：驱动 getObject 返回 Long，直接 (Integer) 强转会 CCE
                    row.setRowCount((int) rs.getLong("row_count"));
                    row.setErrorCount((int) rs.getLong("error_count"));
                    row.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                    var finished = rs.getTimestamp("finished_at");
                    row.setFinishedAt(finished == null ? null : finished.toLocalDateTime());
                    return row;
                });
        return new SystemStatusResponse.ExcelBatches(
                count("excel_import_batch", null),
                count("excel_import_batch", "status = " + ExcelImportBatchEntity.STATUS_DONE),
                count("excel_import_batch", "status = " + ExcelImportBatchEntity.STATUS_FAILED),
                count("excel_import_batch", "status = " + ExcelImportBatchEntity.STATUS_PROCESSING),
                recent.stream().map(b -> new SystemStatusResponse.ExcelBatches.RecentBatch(
                        b.getId(), b.getOriginalFilename(), b.getStatus(),
                        b.getRowCount() == null ? 0 : b.getRowCount(),
                        b.getErrorCount() == null ? 0 : b.getErrorCount(),
                        b.getCreatedAt(), b.getFinishedAt()))
                        .toList());
    }

    /** 近 7 日（含今天）按日计数，JST 日界，缺日补零——图表口径稳定。 */
    private List<SystemStatusResponse.DayCount> clientErrorsPerDay() {
        java.util.Map<String, Long> byDay = new java.util.HashMap<>();
        jdbcTemplate.query(
                "SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS d, COUNT(*) AS c "
                        + "FROM client_error WHERE created_at >= ? GROUP BY d",
                rs -> {
                    byDay.put(rs.getString("d"), rs.getLong("c"));
                },
                LocalDateTime.now(clock).toLocalDate().minusDays(CLIENT_ERROR_DAYS - 1).atStartOfDay());
        return java.util.stream.IntStream.rangeClosed(0, CLIENT_ERROR_DAYS - 1)
                .mapToObj(i -> LocalDateTime.now(clock).toLocalDate().minusDays(CLIENT_ERROR_DAYS - 1 - i).toString())
                .map(date -> new SystemStatusResponse.DayCount(date, byDay.getOrDefault(date, 0L)))
                .toList();
    }

    private long count(String table, String where) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + (where == null ? "" : " WHERE " + where), Long.class);
        return c == null ? 0 : c;
    }
}
