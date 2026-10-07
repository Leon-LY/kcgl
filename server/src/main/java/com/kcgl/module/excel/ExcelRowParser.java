package com.kcgl.module.excel;

import com.kcgl.common.i18n.Msg;
import com.kcgl.common.util.CodeNormalizer;
import com.kcgl.module.itemcode.ItemCodeFormatter;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Excel 行清洗与校验（D-058 E/I）：19 列固定索引 → 类型化行。校验镜像录入控制器
 * （ItemController/CreateItemRequest）——两处口径必须一致，导入不是绕过校验的后门。
 *
 * <p>清洗规则与雅虎 CSV 同源（docs/01 7.4）：代码字段 NFKC+大写；金额 NFKC 后剥
 * ￥/¥/円/逗号/空格；日期 yyyy-MM-dd 与 yyyy/M/d 双格式（Excel 数值/日期单元格由
 * {@link #cellText} 归一为文本后再走双格式）。单行失败=错误行，不连坐全批。
 */
public final class ExcelRowParser {

    /** 单值金额上限（docs/01 五节统一上限）。 */
    static final long MAX_PRICE = 99_999_999L;
    /** 重量上限（CreateItemRequest @Max 对齐）。 */
    static final int MAX_WEIGHT_G = 2_000_000;
    private static final int LEN_SHORT = 32;
    static final int LEN_REMARK = 500;
    private static final int LEN_ITEM_NAME = 200;
    private static final int LEN_CATEGORY = 64;
    private static final int LEN_AUTHOR = 128;
    private static final int LEN_SIZE = 64;
    /** 会场码形态（V1 DDL auction_venue.code CHAR(2) [A-Z]{2}）。 */
    private static final Pattern VENUE_CODE = Pattern.compile("^[A-Z]{2}$");

    /**
     * 行错误 reason 内嵌单元格原文上限（xlsx 单格 ≤32767 字）。不截断则错误采样随行数
     * 无界累积（A3/D-110）：20k 行 × 32KB 原文 = 数百 MB 堆，逼近 OOM。取雅虎管线同口径
     * （{@code YahooOrderParser.REASON_RAW_MAX}），两条导入管线的错误报告内存上界一致。
     */
    private static final int REASON_RAW_MAX = 60;

    /** 日期双格式：ISO 补零与和式斜杠（M/d 宽度自适应，2026/09/05 亦可）。 */
    private static final DateTimeFormatter DATE_SLASH = new DateTimeFormatterBuilder()
            .appendPattern("yyyy/M/d")
            .parseDefaulting(ChronoField.ERA, 1)
            .toFormatter(Locale.JAPAN);

    /** 清洗后的类型化行。itemCode=null 表示批量生成模式（管理番号列空）。 */
    public record ParsedRow(String itemCode, String venueCode, LocalDate buyDate,
            Long purchasePrice, Long fee, Long shippingFee, Long tax, Integer warehouse,
            LocalDate warehouseInDate, String shelfNo, String groupNo, LocalDate photoDate,
            String remark, String itemName, String category, String authorKiln,
            String sizeText, Integer weightG, String salesChannel) {
    }

    /** 解析结果：Ok 或 Err（msg 进批次错误采样：消息键+参数+日文兜底）。 */
    public sealed interface ParseOutcome {
        record Ok(ParsedRow row) implements ParseOutcome {
        }

        record Err(Msg msg) implements ParseOutcome {
            /** 日文兜底文案（落库 error_rows.reason；保持既有调用点与断言不变）。 */
            public String reason() {
                return msg.text();
            }
        }
    }

    private final ExcelProperties props;
    private final LocalDate today;

    /** @param today 批次处理开始日的 JST 日期（未来日校验口径，处理中途不跨天漂移）。 */
    public ExcelRowParser(ExcelProperties props, LocalDate today) {
        this.props = props;
        this.today = today;
    }

    /**
     * 表头契约校验（D-058 E）：前 19 列逐列 trim 比对配置列名；额外尾列忽略
     * （模板外自加列不阻断导入）。返回 null=通过，否则不匹配描述（批次级失败）。
     */
    public Msg headerMismatch(List<String> cells) {
        List<String> expected = props.columns().headerOrder();
        for (int i = 0; i < expected.size(); i++) {
            String actual = i < cells.size() ? cells.get(i).trim() : "";
            if (!expected.get(i).equals(actual)) {
                String column = String.valueOf(i + 1);
                String actualText = shortRaw(actual);
                return Msg.of("imports.batch.excelHeaderMismatch",
                        Map.of("column", column, "expected", expected.get(i), "actual", actualText),
                        column + "列目の見出しが「" + expected.get(i)
                                + "」であるべきですが「" + actualText + "」になっています");
            }
        }
        return null;
    }

    public ParseOutcome parse(List<String> cells) {
        try {
            return new ParseOutcome.Ok(parseOrThrow(cells));
        } catch (RowException e) {
            return new ParseOutcome.Err(e.msg);
        }
    }

    /**
     * FastExcel 原生单元格值归一为文本：数值单元格整数化（1000.0→"1000"，Excel
     * 把手输数字存为 double 的经典形态）；日期单元格转 ISO 文本（POI 以 JVM 默认
     * 时区构造 Date，全链路 TZ=Asia/Tokyo 下与文本路径同语义）。
     */
    public static String cellText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s.trim();
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && !Double.isInfinite(d)
                    ? String.valueOf((long) d)
                    : String.valueOf(d);
        }
        if (value instanceof java.util.Date d) {
            return java.time.Instant.ofEpochMilli(d.getTime())
                    .atZone(java.time.ZoneId.of("Asia/Tokyo")).toLocalDate().toString();
        }
        if (value instanceof java.time.LocalDateTime dt) {
            return dt.toLocalDate().toString();
        }
        if (value instanceof java.time.LocalDate ld) {
            return ld.toString();
        }
        if (value instanceof Boolean b) {
            return String.valueOf(b);
        }
        return value.toString().trim();
    }

    // ------------------------------------------------------------- 内部

    /** 行级结构错误（进错误采样，不抛出方法外）。 */
    private static final class RowException extends RuntimeException {
        private final Msg msg;

        RowException(Msg msg) {
            super(msg.text());
            this.msg = msg;
        }
    }

    private ParsedRow parseOrThrow(List<String> cells) {
        String rawItemCode = cell(cells, 0);
        String itemCode = null;
        if (!rawItemCode.isEmpty()) {
            itemCode = CodeNormalizer.normalize(rawItemCode);
            if (!ItemCodeFormatter.matches(itemCode)) {
                throw rowError("imports.reason.itemCodeFormat", Map.of("raw", shortRaw(rawItemCode)),
                        "管理番号の形式が不正です: " + shortRaw(rawItemCode));
            }
        }

        String rawVenue = cell(cells, 1);
        if (rawVenue.isEmpty()) {
            throw rowError("imports.reason.venueCodeRequired", "会場コードが空です");
        }
        String venueCode = CodeNormalizer.normalize(rawVenue);
        if (!VENUE_CODE.matcher(venueCode).matches()) {
            throw rowError("imports.reason.venueCodeFormat", Map.of("raw", shortRaw(rawVenue)),
                    "会場コードは半角英字2桁で入力してください: " + shortRaw(rawVenue));
        }

        LocalDate buyDate = requiredDateCell(cell(cells, 2), props.columns().buyDate());
        if (buyDate.isAfter(today)) {
            throw rowError("imports.reason.buyDateFuture", "落札日に未来の日付は指定できません");
        }
        Long purchasePrice = moneyCell(cell(cells, 3), props.columns().purchasePrice(), true);
        Long fee = moneyCell(cell(cells, 4), props.columns().fee(), false);
        Long shippingFee = moneyCell(cell(cells, 5), props.columns().shippingFee(), false);
        Long tax = moneyCell(cell(cells, 6), props.columns().tax(), false);
        Integer warehouse = warehouseCell(cell(cells, 7));
        LocalDate warehouseInDate = optionalDateCell(cell(cells, 8), props.columns().warehouseInDate());
        String shelfNo = lengthCell(cell(cells, 9), props.columns().shelfNo(), LEN_SHORT);
        String groupNo = lengthCell(cell(cells, 10), props.columns().groupNo(), LEN_SHORT);
        LocalDate photoDate = optionalDateCell(cell(cells, 11), props.columns().photoDate());
        if (photoDate != null && photoDate.isAfter(today)) {
            throw rowError("imports.reason.photoDateFuture", "撮影日に未来の日付は指定できません");
        }
        String remark = lengthCell(cell(cells, 12), props.columns().remark(), LEN_REMARK);
        String itemName = lengthCell(cell(cells, 13), props.columns().itemName(), LEN_ITEM_NAME);
        String category = lengthCell(cell(cells, 14), props.columns().category(), LEN_CATEGORY);
        String authorKiln = lengthCell(cell(cells, 15), props.columns().authorKiln(), LEN_AUTHOR);
        String sizeText = lengthCell(cell(cells, 16), props.columns().sizeText(), LEN_SIZE);
        Integer weightG = weightCell(cell(cells, 17));
        String salesChannel = lengthCell(cell(cells, 18), props.columns().salesChannel(), LEN_SHORT);

        return new ParsedRow(itemCode, venueCode, buyDate, purchasePrice, fee, shippingFee,
                tax, warehouse, warehouseInDate, shelfNo, groupNo, photoDate, remark, itemName,
                category, authorKiln, sizeText, weightG, salesChannel);
    }

    /** 取固定索引列值（越界=空串——短行由后续必填/格式校验给出精确消息）。 */
    private static String cell(List<String> cells, int index) {
        return index < cells.size() ? cells.get(index).trim() : "";
    }

    /** 行错误 reason 内嵌原文截断（前缀 + 省略号——错误采样内存有界，同雅虎管线口径）。 */
    private static String shortRaw(String raw) {
        return raw.length() > REASON_RAW_MAX ? raw.substring(0, REASON_RAW_MAX) + "…" : raw;
    }

    /**
     * 行错误构造点：message 与 params 在同一处给出，避免两者漂移（params 里的 field
     * 就是配置列名——甲方可改，故不能写死在 i18n 文案里，只能作参数传入）。
     */
    private static RowException rowError(String code, Map<String, Object> params, String message) {
        return new RowException(Msg.of(code, params, message));
    }

    /** 无插值参数的行错误。 */
    private static RowException rowError(String code, String message) {
        return new RowException(Msg.of(code, message));
    }

    /** 必填金额：≥1（仕入単価 @Min(1) 对齐）。 */
    private Long moneyCell(String raw, String name, boolean required) {
        if (raw.isEmpty()) {
            if (required) {
                throw rowError("imports.reason.fieldRequired", Map.of("field", name), name + "が空です");
            }
            return null;
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace("￥", "").replace("¥", "").replace("円", "")
                .replace(",", "").replace(" ", "");
        long value;
        try {
            value = parseWhole(normalized);
        } catch (NumberFormatException e) {
            throw rowError("imports.reason.fieldNotNumber", Map.of("field", name, "raw", shortRaw(raw)),
                    name + "を数値として解釈できません: " + shortRaw(raw));
        }
        if (value > MAX_PRICE) {
            throw rowError("imports.reason.moneyOverMax",
                    Map.of("field", name, "max", String.valueOf(MAX_PRICE)),
                    name + "が上限（" + MAX_PRICE + "円）を超えています");
        }
        if (required && value < 1) {
            throw rowError("imports.reason.moneyBelowMin", Map.of("field", name),
                    name + "は1以上を入力してください");
        }
        if (!required && value < 0) {
            throw rowError("imports.reason.moneyNegative", Map.of("field", name),
                    name + "に負の数は入力できません");
        }
        return value;
    }

    /** 倉庫：1/2/名古屋/福岡（NFKC 后判定——全角１/２同样接受）。 */
    private Integer warehouseCell(String raw) {
        if (raw.isEmpty()) {
            throw rowError("imports.reason.warehouseRequired", "倉庫が空です");
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim();
        return switch (normalized) {
            case "1", "名古屋" -> 1;
            case "2", "福岡" -> 2;
            default -> throw rowError("imports.reason.warehouseFormat", Map.of("raw", shortRaw(raw)),
                    "倉庫は 1／2／名古屋／福岡 で入力してください: " + shortRaw(raw));
        };
    }

    /** 必填日期（落札日）。 */
    private LocalDate requiredDateCell(String raw, String name) {
        if (raw.isEmpty()) {
            throw rowError("imports.reason.fieldRequired", Map.of("field", name), name + "が空です");
        }
        return dateCell(raw, name);
    }

    /** 选填日期（入庫日未来可、撮影日未来不可——未来校验由调用方按字段执行）。 */
    private LocalDate optionalDateCell(String raw, String name) {
        return raw.isEmpty() ? null : dateCell(raw, name);
    }

    private LocalDate dateCell(String raw, String name) {
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim();
        try {
            return LocalDate.parse(normalized); // ISO yyyy-MM-dd
        } catch (DateTimeParseException ignored) {
            // 落到和式斜杠格式
        }
        try {
            return LocalDate.parse(normalized, DATE_SLASH);
        } catch (DateTimeParseException e) {
            throw rowError("imports.reason.fieldNotDate", Map.of("field", name, "raw", shortRaw(raw)),
                    name + "を日付として解釈できません: " + shortRaw(raw));
        }
    }

    /** 自由文本：仅 trim+NFKC 宽度归一风险高（㈱ 等合法字符变形），长度校验后原样保留。 */
    private String lengthCell(String raw, String name, int max) {
        if (raw.length() > max) {
            throw rowError("imports.reason.fieldTooLong", Map.of("field", name, "max", String.valueOf(max)),
                    name + "が" + max + "文字を超えています");
        }
        return raw.isEmpty() ? null : raw;
    }

    private Integer weightCell(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        String label = props.columns().weightG();
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace(",", "").replace(" ", "");
        int value;
        try {
            value = (int) parseWhole(normalized);
        } catch (NumberFormatException e) {
            throw rowError("imports.reason.fieldNotNumber", Map.of("field", label, "raw", shortRaw(raw)),
                    label + "を数値として解釈できません: " + shortRaw(raw));
        }
        if (value < 1 || value > MAX_WEIGHT_G) {
            throw rowError("imports.reason.weightRange",
                    Map.of("field", label, "min", "1", "max", String.valueOf(MAX_WEIGHT_G)),
                    label + "は1～" + MAX_WEIGHT_G + "の範囲で入力してください");
        }
        return value;
    }

    /**
     * 整数解析：接受 "1000" 与 "1000.0"（FastExcel 无模型读取把数值单元格文本化时
     * 常见的小数尾形态；整数值才是合法输入），非整数/非数值抛 NumberFormatException。
     */
    private static long parseWhole(String normalized) {
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException e) {
            double d = Double.parseDouble(normalized); // 非数值在此抛出
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                return (long) d;
            }
            throw new NumberFormatException("not a whole number: " + normalized);
        }
    }
}
