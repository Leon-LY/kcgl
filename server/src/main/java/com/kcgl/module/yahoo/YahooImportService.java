package com.kcgl.module.yahoo;

import tools.jackson.databind.ObjectMapper;
import com.kcgl.common.sse.SseHub;
import com.kcgl.common.sse.SyncEvent;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.yahoo.dto.ImportBatchResponse;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * CSV 异步导入管线（docs/01 7.4 唯一定义）：上传同步段（大小校验→sha256→批次落库
 * →原始文件落盘→投递单线程执行器）毫秒级返回 batchId；处理段（解码→表头校验→
 * 行数上限→逐行清洗+合并（一行一事务坏行不连坐）→计数/错误采样→终态+SSE 广播）。
 *
 * <p>幂等与自愈：file_sha256 唯一=同文件重传 409；启动时把 processing 超阈值的
 * 僵尸批次标记失败（上次进程中断遗留）。错误行采样前 1000 条+计数，防坏文件
 * 把单 JSON cell 撑爆。
 */
@Service
public class YahooImportService {

    private static final Logger log = LoggerFactory.getLogger(YahooImportService.class);

    /** 错误行采样上限（docs/01 5.3 yahoo_import_batch.error_rows）。 */
    static final int ERROR_SAMPLE_LIMIT = 1000;

    private final YahooProperties props;
    private final YahooImportBatchMapper batchMapper;
    private final YahooMergeService mergeService;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final SseHub sseHub;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor executor;

    /** 错误行采样条目（JSON 落库形态）。 */
    public record ErrorRow(long line, String raw, String reason) {
    }

    public YahooImportService(YahooProperties props, YahooImportBatchMapper batchMapper,
            YahooMergeService mergeService, TransactionTemplate txTemplate, Clock clock,
            SseHub sseHub, ObjectMapper objectMapper,
            ThreadPoolTaskExecutor yahooImportExecutor) {
        this.props = props;
        this.batchMapper = batchMapper;
        this.mergeService = mergeService;
        this.txTemplate = txTemplate;
        this.clock = clock;
        this.sseHub = sseHub;
        this.objectMapper = objectMapper;
        this.executor = yahooImportExecutor;
    }

    /** 上传同步段：校验+落库+落盘+投递，返回 processing 批次（处理段后台毫秒级接力）。 */
    public ImportBatchResponse start(MultipartFile file, long userId, String username, String displayName) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.YAHOO_FILE_EMPTY);
        }
        if (file.getSize() > props.maxFileBytes()) {
            throw new BizException(ErrorCode.YAHOO_FILE_TOO_LARGE);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "CSVファイルの読み取りに失敗しました");
        }
        String sha = sha256(bytes);
        try {
            Files.createDirectories(props.importsRoot());
            Files.write(rawPath(sha), bytes); // sha 命名=内容寻址，重传覆盖同字节无增长
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "CSVファイルの保存に失敗しました");
        }

        YahooImportBatchEntity batch = new YahooImportBatchEntity();
        batch.setFileSha256(sha);
        batch.setOriginalFilename(file.getOriginalFilename() == null ? "import.csv" : file.getOriginalFilename());
        batch.setStatus(YahooImportBatchEntity.STATUS_PROCESSING);
        batch.setRowCount(0);
        batch.setMatchedCount(0);
        batch.setUnmatchedCount(0);
        batch.setUpdatedCount(0);
        batch.setUploadedBy(userId);
        try {
            txTemplate.executeWithoutResult(status -> batchMapper.insert(batch));
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.YAHOO_BATCH_DUPLICATE);
        }
        try {
            executor.execute(() -> process(batch.getId(), userId, username, displayName));
        } catch (org.springframework.core.task.TaskRejectedException e) {
            // 队列满：批次转失败但 sha 占位保留——忙时重传同文件 409 而非绕过限流
            txTemplate.executeWithoutResult(status ->
                    failBatch(batch.getId(), "同時インポートが多いため処理できませんでした。しばらくしてからもう一度お試しください"));
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
        return ImportBatchResponse.of(batch, List.of());
    }

    /** 处理段（单线程执行器内）：任何致命异常→批次失败+广播（不向上抛）。 */
    void process(long batchId, long operatorId, String username, String displayName) {
        try {
            YahooImportBatchEntity batch = batchMapper.selectById(batchId);
            byte[] bytes = Files.readAllBytes(rawPath(batch.getFileSha256()));
            YahooCsvDecoder.Decoded decoded = YahooCsvDecoder.decode(bytes);
            processRows(batch, decoded, operatorId, username, displayName);
        } catch (BizException e) {
            txTemplate.executeWithoutResult(status -> failBatch(batchId, e.getMessage()));
            sseHub.broadcast(SyncEvent.TYPE_YAHOO_IMPORT, String.valueOf(batchId), operatorId);
        } catch (Exception e) {
            log.error("yahoo import batch {} failed", batchId, e);
            txTemplate.executeWithoutResult(status -> failBatch(batchId, "インポート処理中にエラーが発生しました"));
            sseHub.broadcast(SyncEvent.TYPE_YAHOO_IMPORT, String.valueOf(batchId), operatorId);
        }
    }

    private void processRows(YahooImportBatchEntity batch, YahooCsvDecoder.Decoded decoded,
            long operatorId, String username, String displayName) throws IOException {
        YahooRowParser parser = new YahooRowParser(props);
        List<YahooImportService.ErrorRow> errors = new ArrayList<>();
        long rows = 0;
        long matched = 0;
        long unmatched = 0;
        long updated = 0;
        try (CSVParser csv = parser.csvFormat().parse(new StringReader(decoded.text()))) {
            List<String> missing = parser.missingColumns(csv.getHeaderNames().toArray(String[]::new));
            if (!missing.isEmpty()) {
                throw new BizException(ErrorCode.VALIDATION,
                        "CSVに必要な列が見つかりません: " + String.join(", ", missing));
            }
            for (CSVRecord record : csv) {
                if (++rows > props.maxRows()) {
                    throw new BizException(ErrorCode.YAHOO_ROW_LIMIT);
                }
                YahooRowParser.ParseOutcome outcome = parser.parse(record);
                if (outcome instanceof YahooRowParser.ParseOutcome.Err err) {
                    errors.add(new ErrorRow(record.getRecordNumber(), abbreviate(record), err.reason()));
                    continue;
                }
                try {
                    YahooMergeService.MergeOutcome merged = mergeService.merge(
                            ((YahooRowParser.ParseOutcome.Ok) outcome).row(),
                            batch.getId(), operatorId, username, displayName);
                    if (merged.matched()) {
                        matched++;
                    } else {
                        unmatched++;
                    }
                    if (!merged.listingFresh()) {
                        updated++;
                    }
                } catch (Exception e) {
                    // 行级合并失败（如并发锁）：记错误行继续——一行不连坐全批
                    log.warn("yahoo import row {} merge failed", record.getRecordNumber(), e);
                    errors.add(new ErrorRow(record.getRecordNumber(), abbreviate(record),
                            "行の処理中にエラーが発生しました"));
                }
            }
        }
        finalizeBatch(batch, decoded.encoding(), rows, matched, unmatched, updated, errors);
    }

    private void finalizeBatch(YahooImportBatchEntity batch, String encoding, long rows,
            long matched, long unmatched, long updated, List<ErrorRow> errors) {
        batch.setEncodingDetected(encoding);
        batch.setRowCount((int) rows);
        batch.setMatchedCount((int) matched);
        batch.setUnmatchedCount((int) unmatched);
        batch.setUpdatedCount((int) updated);
        batch.setErrorRowsJson(toJson(errors));
        batch.setStatus(YahooImportBatchEntity.STATUS_DONE);
        batch.setFinishedAt(LocalDateTime.now(clock));
        txTemplate.executeWithoutResult(status -> batchMapper.updateById(batch));
        sseHub.broadcast(SyncEvent.TYPE_YAHOO_IMPORT, String.valueOf(batch.getId()),
                batch.getUploadedBy());
    }

    private void failBatch(long batchId, String message) {
        YahooImportBatchEntity batch = batchMapper.selectById(batchId);
        if (batch == null || batch.getStatus() != YahooImportBatchEntity.STATUS_PROCESSING) {
            return;
        }
        batch.setStatus(YahooImportBatchEntity.STATUS_FAILED);
        batch.setErrorMessage(message);
        batch.setFinishedAt(LocalDateTime.now(clock));
        batchMapper.updateById(batch);
    }

    /** 启动自愈：上次进程中断遗留的 processing 批次标记失败（sha 占位保留）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverZombieBatches() {
        LocalDateTime threshold = LocalDateTime.now(clock).minusMinutes(props.zombieMinutes());
        List<YahooImportBatchEntity> zombies = batchMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<YahooImportBatchEntity>()
                        .eq(YahooImportBatchEntity::getStatus, YahooImportBatchEntity.STATUS_PROCESSING)
                        .lt(YahooImportBatchEntity::getCreatedAt, threshold));
        for (YahooImportBatchEntity zombie : zombies) {
            log.warn("marking zombie yahoo import batch {} as failed", zombie.getId());
            txTemplate.executeWithoutResult(status ->
                    failBatch(zombie.getId(), "処理が中断されました。ファイルを再確認のうえ再インポートしてください"));
        }
    }

    // ------------------------------------------------------------- 查询与工具

    /** 批次详情（错误行反序列化展示；失败批次无计数）。 */
    public ImportBatchResponse find(long id) {
        YahooImportBatchEntity batch = batchMapper.selectById(id);
        if (batch == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return ImportBatchResponse.of(batch, fromJson(batch.getErrorRowsJson()));
    }

    /** 批次列表（最新 50：10 人店铺的运营回看深度）。 */
    public List<ImportBatchResponse> listRecent() {
        return batchMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<YahooImportBatchEntity>()
                                .orderByDesc(YahooImportBatchEntity::getId)
                                .last("LIMIT 50"))
                .stream()
                .map(batch -> ImportBatchResponse.of(batch, fromJson(batch.getErrorRowsJson())))
                .toList();
    }

    private Path rawPath(String sha) {
        return props.importsRoot().resolve(sha + ".csv");
    }

    private String toJson(List<ErrorRow> errors) {
        try {
            return objectMapper.writeValueAsString(errors.subList(0, Math.min(errors.size(), ERROR_SAMPLE_LIMIT)));
        } catch (RuntimeException e) { // Jackson 3：JacksonException 已 unchecked
            return "[]";
        }
    }

    private List<ErrorRow> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ErrorRow.class));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static String abbreviate(CSVRecord record) {
        String text = String.join(",", record.values());
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
