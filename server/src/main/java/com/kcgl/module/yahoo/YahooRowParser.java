package com.kcgl.module.yahoo;

import com.kcgl.common.util.CodeNormalizer;
import com.kcgl.module.itemcode.ItemCodeFormatter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 行清洗（docs/01 7.4）：管理号 trim+NFKC+大写+正则（不合法=保留原文码作未匹配行，
 * 不丢行）；金额 NFKC 后剥 ￥/¥/円/逗号/空格；日期 M/d H:mm[:ss] 自适应；
 * 结构性缺失（拍品 ID/状态/金额/日期解析失败）=错误行，单行失败不连坐。
 */
public final class YahooRowParser {

    /** 出品状态（yahoo_listing.status 1/2/3 与商品销售态同值）。 */
    public enum ListingStatus {
        ON_SALE(1), SOLD(2), CANCELED(3);

        private final int id;

        ListingStatus(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    /** 清洗后的可合并行。itemCode=null 表示管理号缺失/不合法（未匹配行）。 */
    public record ParsedRow(String auctionId, String rawItemCode, String itemCode,
            Long listPrice, Long soldPrice, ListingStatus status,
            LocalDateTime listedAt, LocalDateTime closedAt) {
    }

    /** 解析结果：Ok 或 Err（reason 进批次错误采样）。 */
    public sealed interface ParseOutcome {
        record Ok(ParsedRow row) implements ParseOutcome {
        }

        record Err(String reason) implements ParseOutcome {
        }
    }

    /** 单值金额上限（docs/01 五节统一上限）。 */
    static final long MAX_PRICE = 99_999_999L;

    /** 雅虎导出常见双格式：补零/不补零、有无秒。 */
    private static final DateTimeFormatter DATETIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy/M/d H:mm")
            .optionalStart().appendPattern(":ss").optionalEnd()
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .toFormatter(Locale.JAPAN);

    private final YahooProperties props;

    public YahooRowParser(YahooProperties props) {
        this.props = props;
    }

    /** CSV 解析格式：文件自带日文表头（首行自动检测+跳过），字段按列名取值。 */
    public CSVFormat csvFormat() {
        return CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();
    }

    /** 表头缺列校验（导入前置，一次校验整文件；缺列=批次配置性失败而非逐行报错）。 */
    public List<String> missingColumns(String[] header) {
        List<String> present = List.of(header);
        List<String> missing = new ArrayList<>();
        for (String column : props.columns().headerOrder()) {
            if (!present.contains(column)) {
                missing.add(column);
            }
        }
        return missing;
    }

    public ParseOutcome parse(CSVRecord record) {
        try {
            return new ParseOutcome.Ok(parseOrThrow(record));
        } catch (RowException e) {
            return new ParseOutcome.Err(e.getMessage());
        }
    }

    // ------------------------------------------------------------- 内部

    /** 行级结构错误（进错误采样，不抛出方法外）。 */
    private static final class RowException extends RuntimeException {
        RowException(String message) {
            super(message);
        }
    }

    private ParsedRow parseOrThrow(CSVRecord record) {
        YahooProperties.Columns cols = props.columns();
        String auctionId = cell(record, cols.auctionId());
        if (auctionId.isEmpty()) {
            throw new RowException("オークションIDが空です");
        }
        if (auctionId.length() > 32) {
            throw new RowException("オークションIDが長すぎます");
        }
        String rawItemCode = cell(record, cols.itemCode());
        String itemCode = null;
        if (!rawItemCode.isEmpty()) {
            itemCode = CodeNormalizer.normalize(rawItemCode);
            if (!ItemCodeFormatter.matches(itemCode)) {
                itemCode = null; // 保留 rawItemCode 供未匹配行展示原文
            }
        }
        return new ParsedRow(auctionId, rawItemCode.isEmpty() ? null : rawItemCode, itemCode,
                priceCell(record, cols.listPrice()), priceCell(record, cols.soldPrice()),
                statusCell(cell(record, cols.status())),
                dateCell(record, cols.listedAt()), dateCell(record, cols.closedAt()));
    }

    /** 取列值：缺列当空串（整文件缺列由 missingColumns 前置拦截）。 */
    private String cell(CSVRecord record, String name) {
        String value = record.isMapped(name) ? record.get(name) : "";
        return value == null ? "" : value.trim();
    }

    private Long priceCell(CSVRecord record, String name) {
        String raw = cell(record, name);
        if (raw.isEmpty()) {
            return null;
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace("￥", "").replace("¥", "").replace("円", "")
                .replace(",", "").replace(" ", "");
        try {
            long value = Long.parseLong(normalized);
            if (value > MAX_PRICE) {
                throw new RowException(name + "が上限（" + MAX_PRICE + "円）を超えています");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new RowException(name + "を数値として解釈できません: " + raw);
        }
    }

    private ListingStatus statusCell(String raw) {
        if (raw.isEmpty()) {
            throw new RowException("状態が空です");
        }
        YahooProperties.StatusValues values = props.statusValues();
        for (String keyword : values.canceled()) {
            if (raw.contains(keyword)) {
                return ListingStatus.CANCELED;
            }
        }
        for (String keyword : values.sold()) {
            if (raw.contains(keyword)) {
                return ListingStatus.SOLD;
            }
        }
        for (String keyword : values.onSale()) {
            if (raw.contains(keyword)) {
                return ListingStatus.ON_SALE;
            }
        }
        throw new RowException("状態を判定できません: " + raw);
    }

    private LocalDateTime dateCell(CSVRecord record, String name) {
        String raw = cell(record, name);
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw, DATETIME);
        } catch (DateTimeParseException e) {
            throw new RowException(name + "を日時として解釈できません: " + raw);
        }
    }
}
