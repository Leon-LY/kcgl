package com.kcgl.common.obs;

import com.kcgl.module.image.ImageProperties;
import com.kcgl.module.inventory.LedgerConsistencyService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 每日自检（M3-⑦，docs/01 5.3/9.3 唯一定义）：账实对账（复用 LedgerConsistencyService
 * 的逐件头寸比对）、管理号计数器一致性（按桶按当前前缀 cur_seq≥MAX(seq_no)，落后=后续
 * 生成必撞 uk）、数据量阈值（item 15 万/流水 300 万/操作日志 300 万）、图片卷磁盘水位（80%）、
 * 图片文件双向对账（孤儿=盘有表无→清理候选；缺失=表有盘无→破图）。备份新鲜度检查
 * 随 M7 backup.sh 状态标记文件一并接入本入口。
 *
 * <p>{@link #check()} 纯只读；{@link #checkAndAlert()} 追加按节落 sys_alert（同
 * dedup 键 upsert 只留最新开启态：连续异常不刷屏，恢复后旧告警留待管理员已读）——
 * 定时任务与管理后台「帳実自検」按钮（POST /api/self-check）共用后者，发现即留痕。
 */
@Service
public class SelfCheckService {

    /** 告警类型常量（sys_alert.type）。 */
    public static final String TYPE_RECONCILE = "RECONCILE_MISMATCH";
    public static final String TYPE_SEQ_COUNTER = "SEQ_COUNTER_MISMATCH";
    public static final String TYPE_DATA_VOLUME = "DATA_VOLUME";
    public static final String TYPE_DISK_USAGE = "DISK_USAGE";
    public static final String TYPE_IMAGE_AUDIT = "IMAGE_AUDIT";

    /** 报告内漂移/样本截断：管理页展示用，完整清单以告警 payload 为准。 */
    private static final int SAMPLE_MAX = 50;

    private final LedgerConsistencyService consistency;
    private final JdbcTemplate jdbcTemplate;
    private final ImageProperties imageProperties;
    private final SelfCheckProperties properties;
    private final AlertService alertService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SelfCheckService(LedgerConsistencyService consistency, JdbcTemplate jdbcTemplate,
            ImageProperties imageProperties, SelfCheckProperties properties,
            AlertService alertService, ObjectMapper objectMapper, Clock clock) {
        this.consistency = consistency;
        this.jdbcTemplate = jdbcTemplate;
        this.imageProperties = imageProperties;
        this.properties = properties;
        this.alertService = alertService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 每日 04:17 JST：避开 03:30 备份窗与整点潮汐；scheduled=false（测试）直通。 */
    @Scheduled(cron = "0 17 4 * * *", zone = "Asia/Tokyo")
    public void runDaily() {
        if (!properties.scheduled()) {
            return;
        }
        checkAndAlert();
    }

    /** 检查并按节落告警（定时任务与手动「帳実自検」共用——发现即留痕）。 */
    public SelfCheckReport checkAndAlert() {
        SelfCheckReport report = check();
        recordAlerts(report);
        return report;
    }

    /** 全量检查（纯只读，不写库）。 */
    public SelfCheckReport check() {
        LedgerConsistencyService.Report ledger = consistency.check();
        LedgerSection ledgerSection = new LedgerSection(ledger.ok(), ledger.balances(),
                ledger.drifts().size(), cap(ledger.drifts(), SAMPLE_MAX));
        CounterSection counters = checkCounters();
        VolumeSection volumes = checkVolumes();
        DiskSection disk = checkDisk();
        ImageAuditSection imageAudit = checkImageFiles();
        boolean ok = ledgerSection.ok() && counters.ok() && volumes.ok() && disk.ok()
                && imageAudit.ok();
        return new SelfCheckReport(LocalDateTime.now(clock), ok,
                ledgerSection, counters, volumes, disk, imageAudit);
    }

    // ------------------------------------------------------------------ 各节检查

    /**
     * 计数器一致性：按桶按<b>当前前缀</b>比较 cur_seq ≥ MAX(seq_no)（桶行丢失=IFNULL −1 仍报）。
     * MAX 必须限定 seq_prefix=cur_prefix：前缀进位（A99→B1）后 cur_seq 归 1，若跨前缀取 MAX
     * 则恒 99>1，单桶过 99 件即永久误报（Excel 旧号导入任意非当前前缀的号同样触发）。
     * 健康=计数器相对当前前缀的已落库号不落后；计数器超前（跳号/导入推进）是正常态。
     */
    private CounterSection checkCounters() {
        List<CounterMismatch> mismatches = jdbcTemplate.query("""
                SELECT b.venue_id, b.buy_month,
                       s.cur_prefix,
                       IFNULL(s.cur_seq, -1) AS cur_seq,
                       IFNULL(pm.max_seq, 0) AS max_seq
                FROM (SELECT DISTINCT venue_id, buy_month FROM item) b
                LEFT JOIN seq_item_code s
                       ON s.venue_id = b.venue_id AND s.month = b.buy_month
                LEFT JOIN (
                    SELECT venue_id, buy_month, seq_prefix, MAX(seq_no) AS max_seq
                    FROM item
                    GROUP BY venue_id, buy_month, seq_prefix
                ) pm
                       ON pm.venue_id = b.venue_id
                      AND pm.buy_month = b.buy_month AND pm.seq_prefix = s.cur_prefix
                WHERE IFNULL(s.cur_seq, -1) < IFNULL(pm.max_seq, 0)
                """, (rs, n) -> new CounterMismatch(rs.getLong("venue_id"),
                rs.getInt("buy_month"), rs.getString("cur_prefix"), rs.getInt("cur_seq"),
                rs.getInt("max_seq")));
        return new CounterSection(mismatches.isEmpty(), mismatches);
    }

    private VolumeSection checkVolumes() {
        long items = count("item");
        long ledgers = count("stock_ledger");
        long logs = count("operation_log");
        boolean ok = items <= properties.itemVolumeThreshold()
                && ledgers <= properties.ledgerVolumeThreshold()
                && logs <= properties.logVolumeThreshold();
        return new VolumeSection(ok, items, ledgers, logs,
                properties.itemVolumeThreshold(), properties.ledgerVolumeThreshold(),
                properties.logVolumeThreshold());
    }

    private long count(String table) {
        Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return c == null ? 0 : c;
    }

    /** 磁盘水位：图片根目录所在卷（本系统唯一可控增长卷）；目录不存在=不可用视为通过。 */
    private DiskSection checkDisk() {
        Path root = Path.of(imageProperties.dir());
        long total = root.toFile().getTotalSpace();
        if (!Files.isDirectory(root) || total <= 0) {
            return new DiskSection(true, imageProperties.dir(), 0, 0, 0);
        }
        long usable = root.toFile().getUsableSpace();
        int usedPercent = (int) Math.round((total - usable) * 100.0 / total);
        return new DiskSection(usedPercent < properties.diskWarnPercent(),
                imageProperties.dir(), total, usable, usedPercent);
    }

    /** 图片文件双向对账：缺失（表有盘无=破图）计失败；孤儿（盘有表无）仅告警不破 ok。 */
    private ImageAuditSection checkImageFiles() {
        Set<String> stored = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT stored_path FROM item_image WHERE stored_path IS NOT NULL", String.class));
        Set<String> thumbs = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT thumb_path FROM item_image WHERE thumb_path IS NOT NULL", String.class));
        Set<String> origOnDisk = walk(imageProperties.origRoot());
        Set<String> thumbOnDisk = walk(imageProperties.thumbRoot());

        List<String> orphans = new ArrayList<>();
        orphans.addAll(notIn(origOnDisk, stored, SAMPLE_MAX));
        orphans.addAll(notIn(thumbOnDisk, thumbs, SAMPLE_MAX));
        List<String> missing = new ArrayList<>();
        missing.addAll(notIn(stored, origOnDisk, SAMPLE_MAX));
        missing.addAll(notIn(thumbs, thumbOnDisk, SAMPLE_MAX));
        long orphanCount = countNotIn(origOnDisk, stored) + countNotIn(thumbOnDisk, thumbs);
        long missingCount = countNotIn(stored, origOnDisk) + countNotIn(thumbs, thumbOnDisk);
        return new ImageAuditSection(missingCount == 0, orphanCount, orphans,
                missingCount, missing);
    }

    /** 相对路径集合（正斜杠规范化）；目录不存在=空集。 */
    private Set<String> walk(Path root) {
        if (!Files.isDirectory(root)) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .forEach(p -> out.add(root.relativize(p).toString().replace('\\', '/')));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static <T> List<T> cap(List<T> list, int max) {
        return list.subList(0, Math.min(max, list.size()));
    }

    private static List<String> notIn(Set<String> from, Set<String> other, int max) {
        return from.stream().filter(p -> !other.contains(p)).limit(max).toList();
    }

    private static long countNotIn(Set<String> from, Set<String> other) {
        return from.stream().filter(p -> !other.contains(p)).count();
    }

    // ------------------------------------------------------------------ 告警落库

    /** 各节独立告警（一节异常不掩盖其余节）；payload 供管理页展开定位。 */
    private void recordAlerts(SelfCheckReport report) {
        if (!report.ledger().ok()) {
            List<String> codes = report.ledger().drifts().stream()
                    .map(LedgerConsistencyService.ItemDrift::itemCode).toList();
            alertService.record(TYPE_RECONCILE, AlertService.LEVEL_ERROR,
                    "帳実不一致が検出されました：" + report.ledger().driftCount()
                            + "件（例: " + String.join("、", cap(codes, 3)) + "）",
                    "self-check-ledger",
                    objectMapper.writeValueAsString(Map.of(
                            "balances", report.ledger().balances(),
                            "drifts", report.ledger().drifts())));
        }
        if (!report.counters().ok()) {
            alertService.record(TYPE_SEQ_COUNTER, AlertService.LEVEL_ERROR,
                    "管理番号カウンタ不整合：" + report.counters().mismatches().size()
                            + "件のカウンタが実データより遅れています",
                    "self-check-counter",
                    objectMapper.writeValueAsString(Map.of(
                            "mismatches", report.counters().mismatches())));
        }
        if (!report.volumes().ok()) {
            alertService.record(TYPE_DATA_VOLUME, AlertService.LEVEL_WARN,
                    "データ量が閾値を超えました：商品 " + report.volumes().itemCount()
                            + "／流水 " + report.volumes().ledgerCount()
                            + "／操作ログ " + report.volumes().logCount(),
                    "self-check-volume",
                    objectMapper.writeValueAsString(Map.of(
                            "itemCount", report.volumes().itemCount(),
                            "ledgerCount", report.volumes().ledgerCount(),
                            "logCount", report.volumes().logCount(),
                            "itemThreshold", report.volumes().itemThreshold(),
                            "ledgerThreshold", report.volumes().ledgerThreshold(),
                            "logThreshold", report.volumes().logThreshold())));
        }
        if (!report.disk().ok()) {
            alertService.record(TYPE_DISK_USAGE, AlertService.LEVEL_ERROR,
                    "ディスク使用率が" + report.disk().usedPercent()
                            + "%に達しました（閾値" + properties.diskWarnPercent() + "%）",
                    "self-check-disk",
                    objectMapper.writeValueAsString(Map.of(
                            "path", report.disk().path(),
                            "totalBytes", report.disk().totalBytes(),
                            "usableBytes", report.disk().usableBytes(),
                            "usedPercent", report.disk().usedPercent())));
        }
        if (report.imageAudit().missingCount() > 0) {
            alertService.record(TYPE_IMAGE_AUDIT, AlertService.LEVEL_ERROR,
                    "画像ファイル欠損：" + report.imageAudit().missingCount()
                            + "件の表参照先に実ファイルがありません",
                    "self-check-image-missing",
                    objectMapper.writeValueAsString(Map.of(
                            "missingCount", report.imageAudit().missingCount(),
                            "missingSamples", report.imageAudit().missingSamples())));
        }
        if (report.imageAudit().orphanCount() > 0) {
            alertService.record(TYPE_IMAGE_AUDIT, AlertService.LEVEL_WARN,
                    "孤立画像ファイル：" + report.imageAudit().orphanCount()
                            + "件が表から未参照です（クリーンアップ候補）",
                    "self-check-image-orphan",
                    objectMapper.writeValueAsString(Map.of(
                            "orphanCount", report.imageAudit().orphanCount(),
                            "orphanSamples", report.imageAudit().orphanSamples())));
        }
    }

    // ------------------------------------------------------------------ 报告模型

    /** ok=五节全过（孤儿文件仅告警不计失败）。 */
    public record SelfCheckReport(LocalDateTime ranAt, boolean ok, LedgerSection ledger,
            CounterSection counters, VolumeSection volumes, DiskSection disk,
            ImageAuditSection imageAudit) {
    }

    public record LedgerSection(boolean ok, List<LedgerConsistencyService.WarehouseBalance> balances,
            int driftCount, List<LedgerConsistencyService.ItemDrift> drifts) {
    }

    /** curSeq=-1 表示桶行丢失（item 已落库但计数器行不在）；maxSeq 仅统计当前前缀（curPrefix）的行。 */
    public record CounterMismatch(long venueId, int month, String curPrefix, int curSeq, int maxSeq) {
    }

    public record CounterSection(boolean ok, List<CounterMismatch> mismatches) {
    }

    public record VolumeSection(boolean ok, long itemCount, long ledgerCount, long logCount,
            long itemThreshold, long ledgerThreshold, long logThreshold) {
    }

    /** totalBytes=0 表示目录不可用（未初始化），视为通过。 */
    public record DiskSection(boolean ok, String path, long totalBytes, long usableBytes,
            int usedPercent) {
    }

    public record ImageAuditSection(boolean ok, long orphanCount, List<String> orphanSamples,
            long missingCount, List<String> missingSamples) {
    }
}
