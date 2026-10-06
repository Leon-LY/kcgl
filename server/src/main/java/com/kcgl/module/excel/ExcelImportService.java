package com.kcgl.module.excel;

import cn.idev.excel.FastExcel;
import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.event.AnalysisEventListener;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kcgl.common.sse.SseHub;
import com.kcgl.common.sse.SyncEvent;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.dict.VenueEntity;
import com.kcgl.module.dict.VenueMapper;
import com.kcgl.module.excel.dto.ExcelImportBatchResponse;
import com.kcgl.module.itemcode.CreateItemCommand;
import com.kcgl.module.itemcode.ItemCodeService;
import com.kcgl.module.itemcode.ItemCodeTxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Excel 双模式导入管线（M4-⑤，D-058 B/C）：上传同步段（空/大小/zip 魔数校验→sha256→
 * 批次落库→原始文件落盘→投递单线程执行器）毫秒级返回 batchId；处理段（表头契约→
 * 行数上限→逐行清洗+落库（一行一事务坏行不连坐）→计数/跳变/错误采样→终态+批次级
 * SSE 广播）。
 *
 * <p>双模式（D-058 C）：管理番号列有值=旧号导入（{@link ItemCodeTxService#insertImportedCode}，
 * 位置序推进桶计数器）；空=批量生成（{@link ItemCodeService#createQuietly}，逐件广播
 * 抑制——批次完成时单次广播替代，2 万行广播=事件风暴）。
 *
 * <p>幂等与自愈：file_sha256 唯一=同文件重传 409（僵尸批次 sha 占位保留，忙时重传
 * 同文件 409 而非绕过限流）；启动时 processing 超阈值的僵尸批次标记失败。行级
 * clientReqId 确定性派生（xli:批次:行号）——未来批次断点续传的幂等地基。
 */
@Service
public class ExcelImportService {

    private static final Logger log = LoggerFactory.getLogger(ExcelImportService.class);

    /** 错误行采样上限（V2 excel_import_batch.error_rows）。 */
    static final int ERROR_SAMPLE_LIMIT = 1000;
    /** note 列宽（V2 DDL excel_import_batch.note VARCHAR(500)）。 */
    private static final int NOTE_MAX = 500;
    /**
     * error_message 列宽（V2 DDL VARCHAR(500)）。表头不符消息内嵌原格文本可超列宽，
     * 不截断则 updateById 在 strict mode 下报 1406，异常于 catch 块内再抛 → 批次永远停在
     * processing（A3/D-110）。对齐雅虎管线的 truncateMessage。
     */
    private static final int ERROR_MESSAGE_MAX = 500;

    private final ExcelProperties props;
    private final ExcelImportBatchMapper batchMapper;
    private final ItemCodeService itemCodeService;
    private final ItemCodeTxService txService;
    private final VenueMapper venueMapper;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final SseHub sseHub;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor executor;

    /** 错误行采样条目（JSON 落库形态）。 */
    public record ErrorRow(long line, String raw, String reason) {
    }

    public ExcelImportService(ExcelProperties props, ExcelImportBatchMapper batchMapper,
            ItemCodeService itemCodeService, ItemCodeTxService txService, VenueMapper venueMapper,
            TransactionTemplate txTemplate, Clock clock, SseHub sseHub, ObjectMapper objectMapper,
            ThreadPoolTaskExecutor excelImportExecutor) {
        this.props = props;
        this.batchMapper = batchMapper;
        this.itemCodeService = itemCodeService;
        this.txService = txService;
        this.venueMapper = venueMapper;
        this.txTemplate = txTemplate;
        this.clock = clock;
        this.sseHub = sseHub;
        this.objectMapper = objectMapper;
        this.executor = excelImportExecutor;
    }

    /** 上传同步段：校验+落库+落盘+投递，返回 processing 批次（处理段后台毫秒级接力）。 */
    public ExcelImportBatchResponse start(MultipartFile file, long userId, String operatorName) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.EXCEL_FILE_INVALID, "Excelファイルが空です");
        }
        if (file.getSize() > props.maxFileBytes()) {
            throw new BizException(ErrorCode.EXCEL_FILE_TOO_LARGE);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "Excelファイルの読み取りに失敗しました");
        }
        if (!isZip(bytes)) {
            // 上传即拒（进管线也读不出）：模板为 xlsx，旧格式 .xls 一并引导转存
            throw new BizException(ErrorCode.EXCEL_FILE_INVALID);
        }
        String sha = sha256(bytes);
        try {
            Files.createDirectories(props.importsRoot());
            Files.write(rawPath(sha), bytes); // sha 命名=内容寻址，重传覆盖同字节无增长
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "Excelファイルの保存に失敗しました");
        }

        ExcelImportBatchEntity batch = new ExcelImportBatchEntity();
        batch.setFileSha256(sha);
        batch.setOriginalFilename(file.getOriginalFilename() == null ? "items.xlsx" : file.getOriginalFilename());
        batch.setStatus(ExcelImportBatchEntity.STATUS_PROCESSING);
        batch.setRowCount(0);
        batch.setGeneratedCount(0);
        batch.setImportedCount(0);
        batch.setErrorCount(0);
        batch.setUploadedBy(userId);
        try {
            txTemplate.executeWithoutResult(status -> batchMapper.insert(batch));
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.EXCEL_BATCH_DUPLICATE);
        }
        try {
            executor.execute(() -> process(batch.getId(), userId, operatorName));
        } catch (org.springframework.core.task.TaskRejectedException e) {
            // 队列满：批次转失败但 sha 占位保留——忙时重传同文件 409 而非绕过限流
            txTemplate.executeWithoutResult(status ->
                    failBatch(batch.getId(), "同時インポートが多いため処理できませんでした。しばらくしてからもう一度お試しください"));
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
        return ExcelImportBatchResponse.of(batch, List.of());
    }

    /** 处理段（单线程执行器内）：任何致命异常→批次失败+广播（不向上抛）。 */
    void process(long batchId, long operatorId, String operatorName) {
        try {
            ExcelImportBatchEntity batch = batchMapper.selectById(batchId);
            ImportRowListener listener =
                    new ImportRowListener(batch, operatorId, operatorName, LocalDate.now(clock));
            try (InputStream in = Files.newInputStream(rawPath(batch.getFileSha256()))) {
                // 第一个工作表=商品（模板约定；headRowNumber(0)=表头行也进回调供契约校验）
                FastExcel.read(in, listener).sheet(0).headRowNumber(0).doRead();
            }
            if (listener.headerError != null) {
                throw new BizException(ErrorCode.EXCEL_FILE_INVALID, listener.headerError);
            }
            if (listener.limitExceeded) {
                throw new BizException(ErrorCode.EXCEL_ROW_LIMIT);
            }
            finalizeBatch(batch, listener);
        } catch (BizException e) {
            txTemplate.executeWithoutResult(status -> failBatch(batchId, e.getMessage()));
            sseHub.broadcast(SyncEvent.TYPE_EXCEL_IMPORT, String.valueOf(batchId), operatorId);
        } catch (Exception e) {
            log.error("excel import batch {} failed", batchId, e);
            txTemplate.executeWithoutResult(status -> failBatch(batchId, "インポート処理中にエラーが発生しました"));
            sseHub.broadcast(SyncEvent.TYPE_EXCEL_IMPORT, String.valueOf(batchId), operatorId);
        }
    }

    /** 终态落库+批次级单次广播（D-058 C：EXCEL_IMPORT 批次域+ITEM 商品域粗粒度失效）。 */
    private void finalizeBatch(ExcelImportBatchEntity batch, ImportRowListener listener) {
        batch.setRowCount((int) listener.dataRows);
        batch.setGeneratedCount((int) listener.generated);
        batch.setImportedCount((int) listener.imported);
        batch.setErrorCount((int) listener.errorCount);
        batch.setErrorRowsJson(toJson(listener.errors));
        batch.setNote(joinNotes(listener.jumpNotes));
        batch.setStatus(ExcelImportBatchEntity.STATUS_DONE);
        batch.setFinishedAt(LocalDateTime.now(clock));
        txTemplate.executeWithoutResult(status -> batchMapper.updateById(batch));
        sseHub.broadcast(SyncEvent.TYPE_EXCEL_IMPORT, String.valueOf(batch.getId()),
                batch.getUploadedBy());
        sseHub.broadcast(SyncEvent.TYPE_ITEM, "", batch.getUploadedBy());
    }

    private void failBatch(long batchId, String message) {
        ExcelImportBatchEntity batch = batchMapper.selectById(batchId);
        if (batch == null || batch.getStatus() != ExcelImportBatchEntity.STATUS_PROCESSING) {
            return;
        }
        batch.setStatus(ExcelImportBatchEntity.STATUS_FAILED);
        batch.setErrorMessage(truncateMessage(message));
        batch.setFinishedAt(LocalDateTime.now(clock));
        batchMapper.updateById(batch);
    }

    /** 落库消息截断（表头不符消息内嵌原格文本可超列宽——对齐雅虎管线 truncateMessage）。 */
    private static String truncateMessage(String message) {
        return message.length() > ERROR_MESSAGE_MAX
                ? message.substring(0, ERROR_MESSAGE_MAX - 1) + "…" : message;
    }

    /** 启动自愈：上次进程中断遗留的 processing 批次标记失败（sha 占位保留）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverZombieBatches() {
        LocalDateTime threshold = LocalDateTime.now(clock).minusMinutes(props.zombieMinutes());
        List<ExcelImportBatchEntity> zombies = batchMapper.selectList(
                new LambdaQueryWrapper<ExcelImportBatchEntity>()
                        .eq(ExcelImportBatchEntity::getStatus, ExcelImportBatchEntity.STATUS_PROCESSING)
                        .lt(ExcelImportBatchEntity::getCreatedAt, threshold));
        for (ExcelImportBatchEntity zombie : zombies) {
            log.warn("marking zombie excel import batch {} as failed", zombie.getId());
            txTemplate.executeWithoutResult(status ->
                    failBatch(zombie.getId(), "処理が中断されました。ファイルを確認のうえ再インポートしてください"));
        }
    }

    // ------------------------------------------------------------- 查询与工具

    /** 批次详情（错误行反序列化展示；失败批次无计数）。 */
    public ExcelImportBatchResponse find(long id) {
        ExcelImportBatchEntity batch = batchMapper.selectById(id);
        if (batch == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return ExcelImportBatchResponse.of(batch, fromJson(batch.getErrorRowsJson()));
    }

    /** 批次列表（最新 50：与雅虎管线同运营回看深度）。 */
    public List<ExcelImportBatchResponse> listRecent() {
        return batchMapper.selectList(
                        new LambdaQueryWrapper<ExcelImportBatchEntity>()
                                .orderByDesc(ExcelImportBatchEntity::getId)
                                .last("LIMIT 50"))
                .stream()
                .map(batch -> ExcelImportBatchResponse.of(batch, fromJson(batch.getErrorRowsJson())))
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

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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

    /** 跳变说明聚合：「、」连接超 500 截断（V2 note 列宽）。 */
    private static String joinNotes(List<String> notes) {
        if (notes.isEmpty()) {
            return null;
        }
        String joined = String.join("、", notes);
        return joined.length() > NOTE_MAX ? joined.substring(0, NOTE_MAX - 1) + "…" : joined;
    }

    // ------------------------------------------------------------- 行监听器

    /**
     * 逐行监听器（非静态内部类：直接用服务依赖）。批次级失败（表头/行数）置标志后
     * 余行只耗解析不耗 DB——不抛出中断读取（包装异常跨版本不可靠），doRead 返回后
     * 由 process 统一转批次失败。
     */
    private final class ImportRowListener extends AnalysisEventListener<Map<Integer, Object>> {
        private final ExcelImportBatchEntity batch;
        private final long operatorId;
        private final String operatorName;
        private final ExcelRowParser parser;
        private final int columnCount;
        /** 会场码→id 缓存（单批次内码集合有限，免逐行查询）。 */
        private final Map<String, Long> venueIdByCode = new HashMap<>();
        final List<ErrorRow> errors = new ArrayList<>();
        final List<String> jumpNotes = new ArrayList<>();
        boolean headerSeen;
        long dataRows;
        /** 错误行**总数**（无界累积的只有这个计数，不是 errors 列表）。 */
        long errorCount;
        long generated;
        long imported;
        String headerError;
        boolean limitExceeded;

        ImportRowListener(ExcelImportBatchEntity batch, long operatorId, String operatorName,
                LocalDate today) {
            this.batch = batch;
            this.operatorId = operatorId;
            this.operatorName = operatorName;
            this.parser = new ExcelRowParser(props, today);
            this.columnCount = props.columns().headerOrder().size();
        }

        @Override
        public void invoke(Map<Integer, Object> row, AnalysisContext context) {
            if (headerError != null || limitExceeded) {
                return;
            }
            List<String> cells = new ArrayList<>(columnCount);
            for (int i = 0; i < columnCount; i++) {
                cells.add(ExcelRowParser.cellText(row.get(i))); // 尾部额外列忽略（D-058 E）
            }
            if (!headerSeen) {
                // 表头行：契约校验（不符置 headerError）后跳过——无论匹配与否都不是数据行。
                // 必须用 headerSeen 标志而非 dataRows==0 判定：后者在表头 return 后仍为 0，
                // 首个数据行会再次进入表头校验 → 整批被「表头不符」误杀
                headerSeen = true;
                headerError = parser.headerMismatch(cells);
                return;
            }
            dataRows++;
            if (dataRows > props.maxRows()) {
                limitExceeded = true;
                return;
            }
            ExcelRowParser.ParseOutcome outcome = parser.parse(cells);
            if (outcome instanceof ExcelRowParser.ParseOutcome.Err err) {
                recordError(cells, err.reason());
                return;
            }
            ExcelRowParser.ParsedRow parsed = ((ExcelRowParser.ParseOutcome.Ok) outcome).row();
            String clientReqId = UUID.nameUUIDFromBytes(
                    ("xli:" + batch.getId() + ":" + dataRows).getBytes(StandardCharsets.UTF_8)).toString();
            try {
                if (parsed.itemCode() != null) {
                    ItemCodeTxService.ImportedCode result = txService.insertImportedCode(
                            toCommand(parsed, clientReqId, null), parsed.itemCode(), parsed.venueCode());
                    imported++;
                    if (result.counterJumpNote() != null) {
                        jumpNotes.add(result.counterJumpNote());
                    }
                } else {
                    itemCodeService.createQuietly(
                            toCommand(parsed, clientReqId, resolveVenueId(parsed.venueCode())));
                    generated++;
                }
            } catch (Exception e) {
                // 行级失败（校验/重复号/并发）：记错误行继续——一行不连坐全批
                log.warn("excel import row {} failed", dataRows + 1, e);
                recordError(cells, reasonOf(e));
            }
        }

        /**
         * 记一条错误：计数**恒增**（{@link #errorCount} 才是总数），采样列表按
         * {@link #ERROR_SAMPLE_LIMIT} 截断。二者分离的原因：解析期若直接用
         * {@code errors.size() < LIMIT} 设界，errorCount 会一并被截成 1000（少报）；
         * 而只留无界列表则 20k 行错误可撑爆堆（A3/D-110）。
         */
        private void recordError(List<String> cells, String reason) {
            errorCount++;
            if (errors.size() < ERROR_SAMPLE_LIMIT) {
                errors.add(new ErrorRow(dataRows + 1, abbreviate(cells), reason));
            }
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            // 终态汇总由 process 在 doRead 返回后执行
        }

        private Long resolveVenueId(String venueCode) {
            return venueIdByCode.computeIfAbsent(venueCode, code -> {
                VenueEntity venue = venueMapper.selectOne(new LambdaQueryWrapper<VenueEntity>()
                        .eq(VenueEntity::getCode, code));
                if (venue == null) {
                    throw new BizException(ErrorCode.VENUE_NOT_FOUND,
                            "会場コード「" + code + "」が登録されていません");
                }
                return venue.getId();
            });
        }

        private String reasonOf(Exception e) {
            return e instanceof BizException biz && biz.getMessage() != null
                    ? biz.getMessage()
                    : "行の処理中にエラーが発生しました";
        }

        private CreateItemCommand toCommand(ExcelRowParser.ParsedRow row, String clientReqId,
                Long venueId) {
            return new CreateItemCommand(clientReqId, null, venueId, row.buyDate(), row.photoDate(),
                    row.purchasePrice(), row.fee(), row.shippingFee(), row.tax(), row.warehouse(),
                    row.shelfNo(), row.warehouseInDate(), row.groupNo(), row.remark(),
                    row.itemName(), row.category(), row.authorKiln(), row.sizeText(), row.weightG(),
                    row.salesChannel(), operatorId, operatorName);
        }

        /** 原始行采样截断（同雅虎管线口径）。 */
        private static String abbreviate(List<String> cells) {
            String text = String.join(",", cells);
            return text.length() > 120 ? text.substring(0, 120) + "…" : text;
        }
    }
}
