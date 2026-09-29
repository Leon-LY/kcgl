package com.kcgl.module.yahoo;

import cn.idev.excel.FastExcel;
import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.event.AnalysisEventListener;
import tools.jackson.databind.ObjectMapper;
import com.kcgl.common.sse.SseHub;
import com.kcgl.common.sse.SyncEvent;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.yahoo.dto.ImportBatchResponse;
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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 受注 xlsx 异步导入管线（docs/01 7.4 唯一定义，D-069）：上传同步段（空/大小/
 * zip 魔数校验→sha256→批次落库→原始文件落盘→投递单线程执行器）毫秒级返回
 * batchId；处理段（FastExcel 读首表→表头契约→行数上限→逐行清洗+逐子行合并
 * （一子行一事务坏行不连坐）→计数/错误采样/まとめ売り補注→终态+SSE 广播）。
 *
 * <p>幂等与自愈：file_sha256 唯一=同文件重传 409；启动时把 processing 超阈值的
 * 僵尸批次标记失败（上次进程中断遗留）。错误行采样前 1000 条+计数，防坏文件
 * 把单 JSON cell 撑爆。rowCount=物理数据行；matched/unmatched 按子行（まとめ
 * 売り一行拆 N 子行，行级幂等键=(商品,拍卖) 对）。
 */
@Service
public class YahooImportService {

    private static final Logger log = LoggerFactory.getLogger(YahooImportService.class);

    /** 错误行采样上限（docs/01 5.3 yahoo_import_batch.error_rows）——累积与序列化同口径，读取期内存随行数有界。 */
    static final int ERROR_SAMPLE_LIMIT = 1000;

    /** note 列宽（V4 DDL yahoo_import_batch.note VARCHAR(500)）。 */
    private static final int NOTE_MAX = 500;

    /** error_message 列宽（V1 DDL VARCHAR(500)）——超宽消息 STRICT 模式下 UPDATE 抛异常会逃逸 catch 卡死批次。 */
    private static final int ERROR_MESSAGE_MAX = 500;

    /** 错误行原文采样单条长度（只拼消费列 5 格，超长截断）。 */
    private static final int ERROR_RAW_ABBREV = 120;

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
            throw new BizException(ErrorCode.INTERNAL, "受注ファイルの読み取りに失敗しました");
        }
        if (!isZip(bytes)) {
            // 上传即拒（进管线也读不出）：受注导出=xlsx（zip 容器），旧 .xls/CSV 引导转存
            throw new BizException(ErrorCode.YAHOO_FILE_INVALID);
        }
        String sha = sha256(bytes);
        try {
            Files.createDirectories(props.importsRoot());
            writeRawAtomically(rawPath(sha), bytes); // sha 命名=内容寻址，重传覆盖同字节无增长
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "受注ファイルの保存に失敗しました");
        }

        YahooImportBatchEntity batch = new YahooImportBatchEntity();
        batch.setFileSha256(sha);
        batch.setOriginalFilename(file.getOriginalFilename() == null ? "orders.xlsx" : file.getOriginalFilename());
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
            OrderRowListener listener = new OrderRowListener(batch, operatorId, username, displayName);
            try (InputStream in = Files.newInputStream(rawPath(batch.getFileSha256()))) {
                // 首个工作表=受注（样张单表约定；headRowNumber(0)=表头行也进回调供契约校验）
                FastExcel.read(in, listener).sheet(0).headRowNumber(0).doRead();
            }
            if (listener.headerError != null) {
                throw new BizException(ErrorCode.YAHOO_FILE_INVALID, listener.headerError);
            }
            if (!listener.headerSeen) {
                // 零行首表不触发任何回调——契约校验必须在此兜底，否则静默 DONE 0 行
                throw new BizException(ErrorCode.YAHOO_FILE_INVALID,
                        "最初のワークシートが空です。受注管理からダウンロードしたファイルか確認してください");
            }
            if (listener.limitExceeded) {
                throw new BizException(ErrorCode.YAHOO_ROW_LIMIT);
            }
            finalizeBatch(batch, listener);
        } catch (BizException e) {
            txTemplate.executeWithoutResult(status -> failBatch(batchId, e.getMessage()));
            sseHub.broadcast(SyncEvent.TYPE_YAHOO_IMPORT, String.valueOf(batchId), operatorId);
        } catch (Exception e) {
            log.error("yahoo import batch {} failed", batchId, e);
            txTemplate.executeWithoutResult(status -> failBatch(batchId, "インポート処理中にエラーが発生しました"));
            sseHub.broadcast(SyncEvent.TYPE_YAHOO_IMPORT, String.valueOf(batchId), operatorId);
        }
    }

    /** 终态落库+批次级单次广播。 */
    private void finalizeBatch(YahooImportBatchEntity batch, OrderRowListener listener) {
        batch.setRowCount((int) listener.dataRows);
        batch.setMatchedCount((int) listener.matched);
        batch.setUnmatchedCount((int) listener.unmatched);
        batch.setUpdatedCount((int) listener.updated);
        batch.setErrorRowsJson(toJson(listener.errors));
        batch.setNote(joinNotes(listener.multiNotes));
        batch.setStatus(YahooImportBatchEntity.STATUS_DONE);
        batch.setFinishedAt(LocalDateTime.now(clock));
        txTemplate.executeWithoutResult(status -> batchMapper.updateById(batch));
        sseHub.broadcast(SyncEvent.TYPE_YAHOO_IMPORT, String.valueOf(batch.getId()),
                batch.getUploadedBy());
    }

    /** 条件置失败：仅 processing 态生效（终态批次不二次改写），单语句原子。消息截断防超宽。 */
    private void failBatch(long batchId, String message) {
        batchMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<YahooImportBatchEntity>()
                .eq(YahooImportBatchEntity::getId, batchId)
                .eq(YahooImportBatchEntity::getStatus, YahooImportBatchEntity.STATUS_PROCESSING)
                .set(YahooImportBatchEntity::getStatus, YahooImportBatchEntity.STATUS_FAILED)
                .set(YahooImportBatchEntity::getErrorMessage, truncateMessage(message))
                .set(YahooImportBatchEntity::getFinishedAt, LocalDateTime.now(clock)));
    }

    /** 落库消息截断（表头不符消息内嵌原格文本可超列宽——对齐 joinNotes 对 NOTE_MAX 的处理）。 */
    private static String truncateMessage(String message) {
        return message.length() > ERROR_MESSAGE_MAX
                ? message.substring(0, ERROR_MESSAGE_MAX - 1) + "…" : message;
    }

    /**
     * 内容寻址落盘=临时文件+改名：并发重传同内容时，对最终路径的截断覆盖写会与
     * 在途读取竞态（读到半截文件）；独占临时文件写完再 REPLACE_EXISTING 改名，
     * 读者要么拿到旧文件要么拿到完整新文件（同 sha=同字节，无中间态）。
     */
    private static void writeRawAtomically(Path target, byte[] bytes) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /**
     * 启动自愈：上次进程中断遗留的 processing 批次标记失败（sha 占位保留）。
     * 显式列集（仅 V1 期列）——前向迁移测试以 target=n 旧库起容器，全列
     * SELECT/UPDATE 会撞 V4 期新列（note）导致上下文起不来。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverZombieBatches() {
        LocalDateTime threshold = LocalDateTime.now(clock).minusMinutes(props.zombieMinutes());
        List<YahooImportBatchEntity> zombies = batchMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<YahooImportBatchEntity>()
                        .select(YahooImportBatchEntity::getId)
                        .eq(YahooImportBatchEntity::getStatus, YahooImportBatchEntity.STATUS_PROCESSING)
                        .lt(YahooImportBatchEntity::getCreatedAt, threshold));
        for (YahooImportBatchEntity zombie : zombies) {
            log.warn("marking zombie yahoo import batch {} as failed", zombie.getId());
            failBatch(zombie.getId(), "処理が中断されました。ファイルを再確認のうえ再インポートしてください");
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
        return props.importsRoot().resolve(sha + ".xlsx");
    }

    /** xlsx=zip 容器魔数（PK\x03\x04，小端序）。 */
    private static boolean isZip(byte[] bytes) {
        return bytes.length >= 4
                && (bytes[0] & 0xFF) == 'P' && (bytes[1] & 0xFF) == 'K'
                && (bytes[2] & 0xFF) == 3 && (bytes[3] & 0xFF) == 4;
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

    /** まとめ売り補注聚合：「、」连接超 500 截断（V4 note 列宽）。 */
    private static String joinNotes(List<String> notes) {
        if (notes.isEmpty()) {
            return null;
        }
        String joined = String.join("、", notes);
        return joined.length() > NOTE_MAX ? joined.substring(0, NOTE_MAX - 1) + "…" : joined;
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ------------------------------------------------------------- 行监听器

    /**
     * 逐行监听器（非静态内部类：直接用服务依赖）。批次级失败（表头/行数）置标志后
     * 余行只耗解析不耗 DB——不抛出中断读取（包装异常跨版本不可靠），doRead 返回后
     * 由 process 统一转批次失败。行级结构错误进采样不连坐全批。
     */
    private final class OrderRowListener extends AnalysisEventListener<Map<Integer, Object>> {
        private final YahooImportBatchEntity batch;
        private final long operatorId;
        private final String username;
        private final String displayName;
        final List<ErrorRow> errors = new ArrayList<>();
        final List<String> multiNotes = new ArrayList<>();
        boolean headerSeen;
        String headerError;
        long dataRows;
        long matched;
        long unmatched;
        long updated;
        boolean limitExceeded;

        OrderRowListener(YahooImportBatchEntity batch, long operatorId,
                String username, String displayName) {
            this.batch = batch;
            this.operatorId = operatorId;
            this.username = username;
            this.displayName = displayName;
        }

        @Override
        public void invoke(Map<Integer, Object> row, AnalysisContext context) {
            if (headerError != null || limitExceeded) {
                return;
            }
            if (!headerSeen) {
                // 表头行：契约校验（不符置 headerError）后跳过——无论匹配与否都不是数据行
                headerSeen = true;
                headerError = YahooOrderParser.headerMismatch(row);
                return;
            }
            if (isBlank(row)) { // 尾部空行（甲方工作表常见）：不计行不报错
                return;
            }
            dataRows++;
            if (dataRows > props.maxRows()) {
                limitExceeded = true;
                return;
            }
            // 物理行号（1 基）——工作表中段夹空行时数据行计数会漂移，错误行报告以真实行号为准
            long physicalLine = context.readRowHolder().getRowIndex() + 1L;
            YahooOrderParser.RowOutcome outcome = YahooOrderParser.parse(row);
            if (outcome instanceof YahooOrderParser.RowOutcome.Err err) {
                if (errors.size() < ERROR_SAMPLE_LIMIT) {
                    errors.add(new ErrorRow(physicalLine, abbreviate(row), err.reason()));
                }
                return;
            }
            for (YahooOrderParser.ParsedRow parsed :
                    ((YahooOrderParser.RowOutcome.Ok) outcome).rows()) {
                try {
                    YahooMergeService.MergeOutcome merged = mergeService.merge(
                            parsed, batch.getId(), operatorId, username, displayName);
                    if (merged.matched()) {
                        matched++;
                    } else {
                        unmatched++;
                    }
                    if (!merged.listingFresh()) {
                        updated++;
                    }
                } catch (Exception e) {
                    // 子行级合并失败（如并发锁）：记错误行继续——一子行不连坐全批
                    log.warn("yahoo import row {} merge failed", physicalLine, e);
                    if (errors.size() < ERROR_SAMPLE_LIMIT) {
                        errors.add(new ErrorRow(physicalLine, abbreviate(row),
                                "行の処理中にエラーが発生しました"));
                    }
                }
            }
            if (((YahooOrderParser.RowOutcome.Ok) outcome).rows().size() > 1) {
                multiNotes.add(multiNoteOf(physicalLine,
                        ((YahooOrderParser.RowOutcome.Ok) outcome).rows()));
            }
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            // 终态汇总由 process 在 doRead 返回后执行
        }

        /** まとめ売り補注（D-069 4：导入报告行提示单价未分割）。 */
        private String multiNoteOf(long line, List<YahooOrderParser.ParsedRow> rows) {
            String codes = rows.stream()
                    .map(row -> row.rawItemCode() != null ? row.rawItemCode() : "（コードなし）")
                    .reduce((a, b) -> a + "・" + b).orElse("");
            String anchor = rows.get(0).orderId() != null
                    ? "注文" + rows.get(0).orderId() : line + "行目";
            return anchor + "（" + codes + "）は複数商品のため単価が未分割です";
        }

        /** 全空行判定（FastExcel 对空行可能回调空 map 或全 null 值）。 */
        private static boolean isBlank(Map<Integer, Object> row) {
            return row.values().stream().allMatch(
                    value -> value == null || YahooOrderParser.cellText(value).isEmpty());
        }

        /** 原始行采样截断（只拼消费列，甲方手工列不进错误报告）。 */
        private static String abbreviate(Map<Integer, Object> row) {
            StringBuilder sb = new StringBuilder();
            for (int col : new int[]{YahooOrderParser.COL_ORDER_ID, YahooOrderParser.COL_ITEM_CODE,
                    YahooOrderParser.COL_ORDER_TIME, YahooOrderParser.COL_AUCTION_ID,
                    YahooOrderParser.COL_UNIT_PRICE}) {
                if (!sb.isEmpty()) {
                    sb.append(',');
                }
                sb.append(YahooOrderParser.cellText(row.get(col)));
            }
            String text = sb.toString();
            return text.length() > ERROR_RAW_ABBREV ? text.substring(0, ERROR_RAW_ABBREV) + "…" : text;
        }
    }
}
