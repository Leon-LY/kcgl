package com.kcgl.module.yahoo;

import com.kcgl.common.util.CodeNormalizer;
import com.kcgl.module.itemcode.ItemCodeFormatter;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 受注 xlsx 行清洗（docs/01 7.4，D-069 列映射钉死）：A=OrderId（留痕）、
 * B=自码（匹配键，trim+大写归一，**不得从 Title 解析**——样张 row 17 自码未嵌
 * Title 证明嵌码只是巧合冗余）、C=OrderTime（显示文本「2026-9-17 10:02」/序列
 * 数值双形态→JST 墙钟 LocalDateTime）、
 * D=YahooAuctionId（幂等键）、P=UnitPrice（落札价）；表头外列（甲方手工工作列
 * W/X/Y/Z 等）全忽略。
 *
 * <p>まとめ売り：B 列换行分隔多码同一拍卖 → 一行拆 N 个子行（每件都售出），
 * UnitPrice=合计价无拆分依据 → soldPrice 留空走手填后补（A10 优先级链已支持）。
 * 单行失败=错误行，不连坐全批。
 */
public final class YahooOrderParser {

    /** 消费列的固定索引（受注导出 A-U 官方 21 列布局，D-069）。 */
    public static final int COL_ORDER_ID = 0;
    public static final int COL_ITEM_CODE = 1;
    public static final int COL_ORDER_TIME = 2;
    public static final int COL_AUCTION_ID = 3;
    public static final int COL_UNIT_PRICE = 15;

    /** 表头契约（消费列位置×官方列名——校验防错传文件，不符即批次配置性失败）。 */
    private static final Map<Integer, String> EXPECTED_HEADERS = Map.of(
            COL_ORDER_ID, "OrderId",
            COL_ITEM_CODE, "YahooAuctionMerchantId",
            COL_ORDER_TIME, "OrderTime",
            COL_AUCTION_ID, "YahooAuctionId",
            COL_UNIT_PRICE, "UnitPrice");

    /** 单值金额上限（docs/01 五节统一上限）。 */
    static final long MAX_PRICE = 99_999_999L;

    /** ID/自码列宽上限（OrderId/YahooAuctionId 与 B 列自码 raw_item_code 均 VARCHAR(32)）。 */
    private static final int MAX_ID_LENGTH = 32;

    /**
     * B 列单行码数上限（まとめ売り实测 2 件）。B 格 ≤32767 字可载数千码——无上限
     * 则单行可放大出上万子行事务占死单线程管线（安全评审 MEDIUM-2）。
     */
    static final int MAX_CODES_PER_ROW = 32;

    /** 行错误 reason 内嵌单元格原文上限（xlsx 单格 ≤32767 字——不截断则错误采样随行数无界累积，安全评审 MEDIUM-1）。 */
    private static final int REASON_RAW_MAX = 60;

    /** Excel 序列日期纪元（1900 伪闰年修正后的标准换算；serial≥61 即正确）。 */
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    /**
     * 显示文本形态日期的容错格式（FastExcel Map 模式把日期格式单元格投递为
     * 显示文本——样张实测「2026-9-17 10:02」：分隔符 - 与 / 混见、月/日/时不
     * 补零、秒位可有可无；解析前统一 / → -）。
     */
    private static final DateTimeFormatter TEXT_DATETIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-M-d H:mm")
            .optionalStart().appendPattern(":ss").optionalEnd()
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .toFormatter(Locale.JAPAN);

    /** 清洗后的可合并子行（まとめ売り一行多子行）。itemCode=null 表示未命中新码形。 */
    public record ParsedRow(String orderId, String auctionId, String rawItemCode, String itemCode,
            Long soldPrice, LocalDateTime orderTime, boolean multiItem) {
    }

    /** 一行（物理 xlsx 行）的解析结果：Ok（1..N 子行）或 Err（reason 进批次错误采样）。 */
    public sealed interface RowOutcome {
        record Ok(List<ParsedRow> rows) implements RowOutcome {
        }

        record Err(String reason) implements RowOutcome {
        }
    }

    /** 表头契约校验（首行一次，缺列/错列=批次配置性失败而非逐行报错）。 */
    public static String headerMismatch(Map<Integer, Object> headerCells) {
        List<String> problems = new ArrayList<>();
        for (var entry : EXPECTED_HEADERS.entrySet()) {
            String actual = cellText(headerCells.get(entry.getKey()));
            if (!entry.getValue().equals(actual)) {
                problems.add((char) ('A' + entry.getKey()) + "列は「" + entry.getValue()
                        + "」であるべきですが「" + (actual.isEmpty() ? "（空）" : actual) + "」です");
            }
        }
        return problems.isEmpty() ? null : String.join("、", problems);
    }

    public static RowOutcome parse(Map<Integer, Object> cells) {
        try {
            return new RowOutcome.Ok(parseOrThrow(cells));
        } catch (RowException e) {
            return new RowOutcome.Err(e.getMessage());
        }
    }

    // ------------------------------------------------------------- 单元格归一

    /**
     * FastExcel 原生单元格值归一为文本：数值单元格整数化（Excel 把手输数字存为
     * double 的经典形态）；日期单元格转 JST 本地时间文本（与 ExcelRowParser 同源）。
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
        if (value instanceof LocalDateTime dt) {
            return dt.toString();
        }
        if (value instanceof LocalDate ld) {
            return ld.toString();
        }
        if (value instanceof java.util.Date d) {
            return dateWallClock(d).toString();
        }
        if (value instanceof Boolean b) {
            return String.valueOf(b);
        }
        return value.toString().trim();
    }

    /**
     * java.util.Date → 工作簿墙钟：POI DateUtil.getJavaDate 约定=序列墙钟按 UTC
     * 装载（Map 模式实际投递显示文本，Date 仅模型类读取路径产出）。按 UTC 复原=
     * 墙钟原样，且不随 JVM 默认时区漂移（按 JST 复原则换部署环境即偏移）。
     */
    private static LocalDateTime dateWallClock(java.util.Date date) {
        return LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
    }

    /** 成交时刻：显示文本（Map 模式原生形态）/序列数值/日期对象三态 → LocalDateTime。 */
    static LocalDateTime orderTimeCell(Object value) {
        if (value == null || value instanceof String s && s.isBlank()) {
            throw new RowException("注文日時が空です");
        }
        if (value instanceof Number n) {
            return serialToLocalDateTime(n.doubleValue());
        }
        if (value instanceof LocalDateTime dt) {
            return dt;
        }
        if (value instanceof LocalDate ld) {
            return ld.atStartOfDay();
        }
        if (value instanceof java.util.Date d) {
            return dateWallClock(d);
        }
        String raw = cellText(value);
        try {
            return serialToLocalDateTime(Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            try {
                return LocalDateTime.parse(raw.replace('/', '-'), TEXT_DATETIME);
            } catch (DateTimeParseException e2) {
                throw new RowException("注文日時を解釈できません: " + shortRaw(raw));
            }
        }
    }

    /**
     * Excel 序列日期 → LocalDateTime（1899-12-30 纪元；小数部分=日内时刻）。
     * 秒级取整：double 仅 ~15-16 位有效数字，46282.418206018519 的尾数实存
     * ~77ns 残差（样张 10:02:13 会解成 …13.000000077）——受注时刻秒级即真值。
     */
    private static LocalDateTime serialToLocalDateTime(double serial) {
        long days = (long) Math.floor(serial);
        long secondOfDay = Math.round((serial - days) * 86_400L);
        if (secondOfDay >= 86_400L) { // 浮点尾数溢出防护（x.9999… → 次日 0 点）
            days += 1;
            secondOfDay -= 86_400L;
        }
        return EXCEL_EPOCH.plusDays(days).atTime(java.time.LocalTime.ofSecondOfDay(secondOfDay));
    }

    /** 落札价：数值/文本两态（文本走 NFKC+￥/円/千分位剥离——手输单元格容错）。 */
    static Long priceCell(Object value) {
        if (value == null || value instanceof String s && s.isBlank()) {
            return null;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (d != Math.rint(d) || d < 0 || d > MAX_PRICE) {
                throw new RowException("落札価格が不正です: " + shortRaw(cellText(value)));
            }
            return (long) d;
        }
        String raw = cellText(value);
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace("￥", "").replace("¥", "").replace("円", "")
                .replace(",", "").replace(" ", "");
        try {
            long price = Long.parseLong(normalized);
            if (price < 0) { // 文本分支与 Number 分支同口径（负价=不正，非落札事实）
                throw new RowException("落札価格が不正です: " + shortRaw(raw));
            }
            if (price > MAX_PRICE) {
                throw new RowException("落札価格が上限（" + MAX_PRICE + "円）を超えています");
            }
            return price;
        } catch (NumberFormatException e) {
            throw new RowException("落札価格を数値として解釈できません: " + shortRaw(raw));
        }
    }

    // ------------------------------------------------------------- 内部

    /** 行级结构错误（进错误采样，不抛出方法外）。 */
    static final class RowException extends RuntimeException {
        RowException(String message) {
            super(message);
        }
    }

    private static List<ParsedRow> parseOrThrow(Map<Integer, Object> cells) {
        String auctionId = idCell(cells.get(COL_AUCTION_ID), "オークションID");
        if (auctionId == null) {
            throw new RowException("オークションIDが空です"); // 幂等键，结构性必填
        }
        String orderId = idCell(cells.get(COL_ORDER_ID), "注文ID");
        LocalDateTime orderTime = orderTimeCell(cells.get(COL_ORDER_TIME));
        Long unitPrice = priceCell(cells.get(COL_UNIT_PRICE));
        List<String> codes = codesOf(cells.get(COL_ITEM_CODE));
        boolean multiItem = codes.size() > 1;
        // まとめ売り：UnitPrice=合计价，无拆分依据 → 留空走手填后补（D-069 4）
        Long soldPrice = multiItem ? null : unitPrice;

        List<ParsedRow> rows = new ArrayList<>();
        for (String rawCode : codes) {
            String itemCode = null;
            if (!rawCode.isEmpty()) {
                String normalized = CodeNormalizer.normalize(rawCode);
                if (ItemCodeFormatter.matches(normalized)) {
                    itemCode = normalized;
                }
            }
            rows.add(new ParsedRow(orderId, auctionId,
                    rawCode.isEmpty() ? null : rawCode, itemCode,
                    soldPrice, orderTime, multiItem));
        }
        if (rows.isEmpty()) { // B 列全空：单占位子行（订单存在但自码缺失=未匹配行）
            rows.add(new ParsedRow(orderId, auctionId, null, null,
                    soldPrice, orderTime, false));
        }
        return rows;
    }

    /** B 列自码拆分：换行分隔（まとめ売り），逐个 trim；空段丢弃。超宽码/超量码=行错误。 */
    private static List<String> codesOf(Object value) {
        String raw = cellText(value);
        if (raw.isEmpty()) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (String part : raw.split("\\r\\n|\\r|\\n")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                if (trimmed.length() > MAX_ID_LENGTH) {
                    throw new RowException("商品コードが長すぎます");
                }
                codes.add(trimmed);
            }
        }
        if (codes.size() > MAX_CODES_PER_ROW) {
            throw new RowException("1行の商品コード数が多すぎます（最大" + MAX_CODES_PER_ROW + "）");
        }
        return codes;
    }

    /** 行错误 reason 内嵌原文截断（label + 前缀 + 省略号——错误采样内存有界）。 */
    private static String shortRaw(String raw) {
        return raw.length() > REASON_RAW_MAX ? raw.substring(0, REASON_RAW_MAX) + "…" : raw;
    }

    /** ID 列（OrderId/YahooAuctionId）：文本化+长度校验；OrderId 允许空。 */
    private static String idCell(Object value, String label) {
        String text = cellText(value);
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > MAX_ID_LENGTH) {
            throw new RowException(label + "が長すぎます");
        }
        return text;
    }
}
