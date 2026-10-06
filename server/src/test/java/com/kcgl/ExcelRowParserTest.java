package com.kcgl;

import com.kcgl.module.excel.ExcelProperties;
import com.kcgl.module.excel.ExcelRowParser;
import com.kcgl.module.excel.ExcelRowParser.ParseOutcome;
import com.kcgl.module.excel.ExcelRowParser.ParsedRow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Excel 行清洗与校验单测（D-058 E/I）：表头契约/单元格归一/双格式日期/金额清洗/
 * 仓库名映射/长度与范围。today 固定 2026-09-28（未来日校验不随墙钟漂移）。
 */
class ExcelRowParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);
    private static final ExcelProperties PROPS = ExcelProperties.defaults();

    private final ExcelRowParser parser = new ExcelRowParser(PROPS, TODAY);

    // ------------------------------------------------------------- 表头契约

    @Test
    void headerMismatch_defaultHeaders_returnsNull() {
        assertThat(parser.headerMismatch(PROPS.columns().headerOrder())).isNull();
    }

    @Test
    void headerMismatch_wrongHeader_reportsColumnNumberAndBothNames() {
        List<String> cells = new ArrayList<>(PROPS.columns().headerOrder());
        cells.set(2, "購入日");
        String message = parser.headerMismatch(cells);
        assertThat(message).contains("3列目").contains("落札日").contains("購入日");
    }

    @Test
    void headerMismatch_shortHeader_reportsMissingColumnAsEmpty() {
        List<String> cells = PROPS.columns().headerOrder().subList(0, 18);
        String message = parser.headerMismatch(cells);
        assertThat(message).contains("19列目").contains("販売チャネル");
    }

    @Test
    void headerMismatch_extraTrailingColumns_ignored() {
        List<String> cells = new ArrayList<>(PROPS.columns().headerOrder());
        cells.add("追加メモ");
        cells.add("予備");
        assertThat(parser.headerMismatch(cells)).isNull();
    }

    @Test
    void headerMismatch_whitespaceAroundHeader_trimmedBeforeCompare() {
        List<String> cells = new ArrayList<>();
        for (String name : PROPS.columns().headerOrder()) {
            cells.add(" " + name + " ");
        }
        assertThat(parser.headerMismatch(cells)).isNull();
    }

    // ------------------------------------------------------------- 单元格归一

    @Test
    void cellText_variants_normalizedToString() {
        assertThat(ExcelRowParser.cellText(null)).isEmpty();
        assertThat(ExcelRowParser.cellText("  HT  ")).isEqualTo("HT");
        assertThat(ExcelRowParser.cellText(1000.0d)).isEqualTo("1000");
        assertThat(ExcelRowParser.cellText(1000.5d)).isEqualTo("1000.5");
        assertThat(ExcelRowParser.cellText(7)).isEqualTo("7");
        assertThat(ExcelRowParser.cellText(Boolean.TRUE)).isEqualTo("true");
    }

    @Test
    void cellText_excelDateCell_convertedViaTokyoZone() {
        // POI 把日期单元格读为 java.util.Date（epoch 毫秒）——固定正午避免时区日界抖动
        Date date = Date.from(LocalDateTime.of(2026, 9, 15, 12, 0)
                .atZone(ZoneId.of("Asia/Tokyo")).toInstant());
        assertThat(ExcelRowParser.cellText(date)).isEqualTo("2026-09-15");
        assertThat(ExcelRowParser.cellText(LocalDateTime.of(2026, 9, 15, 8, 30)))
                .isEqualTo("2026-09-15");
        assertThat(ExcelRowParser.cellText(LocalDate.of(2026, 9, 15))).isEqualTo("2026-09-15");
    }

    // ------------------------------------------------------------- 行解析

    /** 19 列标准行（旧号模式，全字段填充）。 */
    private static List<String> row(String itemCode, String venueCode, String buyDate,
            String price, String fee, String shipping, String tax, String warehouse,
            String inDate, String shelf, String group, String photoDate, String remark,
            String itemName, String category, String author, String size, String weight,
            String channel) {
        return List.of(itemCode, venueCode, buyDate, price, fee, shipping, tax, warehouse,
                inDate, shelf, group, photoDate, remark, itemName, category, author, size,
                weight, channel);
    }

    private static List<String> minimalGeneratedRow() {
        return row("", "HT", "2026-09-01", "12000", "", "", "", "名古屋",
                "", "", "", "", "", "", "", "", "", "", "");
    }

    @Test
    void parse_fullOldCodeRow_returnsTypedRow() {
        List<String> cells = row("HT9-A5X", "HT", "2026/9/1", "￥12,000", "500", "￥800",
                "1,100", "福岡", "2026-09-20", "F-12", "G3", "2026/09/25", "傷あり",
                "信楽焼 花瓶", "陶磁器", "作者X", "高さ30cm", "1,200", "ヤフオク");
        ParseOutcome outcome = parser.parse(cells);
        assertThat(outcome).isInstanceOf(ParseOutcome.Ok.class);
        ParsedRow parsed = ((ParseOutcome.Ok) outcome).row();
        assertThat(parsed.itemCode()).isEqualTo("HT9-A5X");
        assertThat(parsed.venueCode()).isEqualTo("HT");
        assertThat(parsed.buyDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(parsed.purchasePrice()).isEqualTo(12_000L);
        assertThat(parsed.fee()).isEqualTo(500L);
        assertThat(parsed.shippingFee()).isEqualTo(800L);
        assertThat(parsed.tax()).isEqualTo(1_100L);
        assertThat(parsed.warehouse()).isEqualTo(2);
        assertThat(parsed.warehouseInDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(parsed.shelfNo()).isEqualTo("F-12");
        assertThat(parsed.groupNo()).isEqualTo("G3");
        assertThat(parsed.photoDate()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(parsed.remark()).isEqualTo("傷あり");
        assertThat(parsed.itemName()).isEqualTo("信楽焼 花瓶");
        assertThat(parsed.category()).isEqualTo("陶磁器");
        assertThat(parsed.authorKiln()).isEqualTo("作者X");
        assertThat(parsed.sizeText()).isEqualTo("高さ30cm");
        assertThat(parsed.weightG()).isEqualTo(1_200);
        assertThat(parsed.salesChannel()).isEqualTo("ヤフオク");
    }

    @Test
    void parse_emptyItemCode_generatesModeWithNullCode() {
        ParsedRow parsed = ((ParseOutcome.Ok) parser.parse(minimalGeneratedRow())).row();
        assertThat(parsed.itemCode()).isNull();
        assertThat(parsed.purchasePrice()).isEqualTo(12_000L);
        assertThat(parsed.warehouse()).isEqualTo(1);
        assertThat(parsed.fee()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            // 会場コード：NFKC+大文字化（全角小文字手輸入容错）
            "ｈｔ, HT",
            " ht , HT",
    })
    void parse_venueCodeVariants_normalized(String raw, String expected) {
        List<String> cells = row("", raw, "2026-09-01", "1000", "", "", "", "1",
                "", "", "", "", "", "", "", "", "", "", "");
        ParsedRow parsed = ((ParseOutcome.Ok) parser.parse(cells)).row();
        assertThat(parsed.venueCode()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "2", "名古屋", "福岡"})
    void parse_warehouseAcceptedForms_mapsToIds(String raw) {
        List<String> cells = row("", "HT", "2026-09-01", "1000", "", "", "", raw,
                "", "", "", "", "", "", "", "", "", "", "");
        Integer warehouse = ((ParseOutcome.Ok) parser.parse(cells)).row().warehouse();
        assertThat(warehouse).isIn(1, 2);
    }

    @Test
    void parse_fullwidthDigitsAndDoubleFormMoney_normalized() {
        // 全角数字１０００／小数尾 1000.0（数值单元格文本化经典形态）均须可解析
        List<String> cells = row("", "HT", "2026-09-01", "１０００", "1000.0", "", "", "1",
                "", "", "", "", "", "", "", "", "", "", "");
        ParsedRow parsed = ((ParseOutcome.Ok) parser.parse(cells)).row();
        assertThat(parsed.purchasePrice()).isEqualTo(1_000L);
        assertThat(parsed.fee()).isEqualTo(1_000L);
    }

    @Test
    void parse_warehouseInDateFutureAllowed_todayBackdatedRejected() {
        List<String> future = row("", "HT", "2026-09-01", "1000", "", "", "", "1",
                "2027-01-01", "", "", "", "", "", "", "", "", "", "");
        assertThat(parser.parse(future)).isInstanceOf(ParseOutcome.Ok.class);

        List<String> futurePhoto = row("", "HT", "2026-09-01", "1000", "", "", "", "1",
                "", "", "", "2026-09-29", "", "", "", "", "", "", "");
        assertThat(parser.parse(futurePhoto)).isInstanceOf(ParseOutcome.Err.class);
    }

    @Test
    void parse_itemCodeWithoutBand_acceptedAsOldCodeForm() {
        // 正则价格码可选（include_price_code=false 口径的存量旧号无末位字母）
        List<String> cells = row("HT9-A5", "HT", "2026-09-01", "1000", "", "", "", "1",
                "", "", "", "", "", "", "", "", "", "", "");
        ParsedRow parsed = ((ParseOutcome.Ok) parser.parse(cells)).row();
        assertThat(parsed.itemCode()).isEqualTo("HT9-A5");
    }

    @ParameterizedTest
    @CsvSource({
            // 管理番号形式不正（行内即拦，不进 DB 层；流水 0 不合法 [1-9][0-9]?）
            "HT9-A0X, 管理番号",
            // 会場コード空/形式不正
            "'', 会場コード",
            "ht9, 会場コード",
            // 落札日未来/形式不正/空
            "2026-09-29, 落札日",
            "9/15, 落札日",
            "'', 落札日",
            // 仕入単価空/0/負数/非数値/上限超過
            "'', 仕入単価",
            "0, 仕入単価",
            "-100, 仕入単価",
            "abc, 仕入単価",
            "100000000, 仕入単価",
            // 倉庫空/不正値
            "'', 倉庫",
            "3, 倉庫",
            // 任意金額の負数
            "-5, 手数料",
            // 重量範囲
            "0, 重量",
            "2000001, 重量",
    })
    void parse_invalidCells_reportsReasonWithColumnName(String raw, String columnHint) {
        // row() 返回 List.of 不可变——本用例需按列覆写，须包一层 ArrayList
        List<String> cells = new ArrayList<>(row("", "HT", "2026-09-01", "1000", "", "", "", "1",
                "", "", "", "", "", "", "", "", "", "", ""));
        switch (columnHint) {
            case "管理番号" -> cells.set(0, raw);
            case "会場コード" -> cells.set(1, raw);
            case "落札日" -> cells.set(2, raw);
            case "仕入単価" -> cells.set(3, raw);
            case "手数料" -> cells.set(4, raw);
            case "倉庫" -> cells.set(7, raw);
            case "重量" -> cells.set(17, raw);
            default -> throw new IllegalArgumentException("unknown hint: " + columnHint);
        }
        ParseOutcome outcome = parser.parse(cells);
        assertThat(outcome).isInstanceOf(ParseOutcome.Err.class);
        assertThat(((ParseOutcome.Err) outcome).reason()).contains(columnHint);
    }

    @Test
    void parse_overlongFreeText_reportsLimit() {
        List<String> cells = row("", "HT", "2026-09-01", "1000", "", "", "", "1",
                "", "", "", "", "", "あ".repeat(201), "", "", "", "", "");
        ParseOutcome outcome = parser.parse(cells);
        assertThat(outcome).isInstanceOf(ParseOutcome.Err.class);
        assertThat(((ParseOutcome.Err) outcome).reason()).contains("200");
    }

    @Test
    @Tag("regression")
    void parse_hugeRawCell_reasonEmbedsTruncatedPrefixOnly() {
        // xlsx 单格 ≤32767 字。错误 reason 内嵌原文若不截断，错误采样内存随行数无界累积
        // （20k 行 x 32KB 原文 ≈ 数百 MB 堆）。守卫：内嵌原文有界（A3/D-110）。
        List<String> cells = new ArrayList<>(row("あ".repeat(30_000), "HT", "2026-09-01", "1000",
                "", "", "", "1", "", "", "", "", "", "", "", "", "", "", ""));
        ParseOutcome outcome = parser.parse(cells);
        assertThat(outcome).isInstanceOf(ParseOutcome.Err.class);
        String reason = ((ParseOutcome.Err) outcome).reason();
        assertThat(reason).contains("管理番号").endsWith("…");
        assertThat(reason).as("原文 3 万字须被截到上界内，而非原样内嵌").hasSizeLessThan(200);
    }

    @Test
    @Tag("regression")
    void headerMismatch_hugeHeaderCell_reportsTruncatedActualOnly() {
        // 表头不符消息同样内嵌原格文本：不截断则消息超 error_message VARCHAR(500)，
        // strict mode 下 1406 在 catch 块内再抛 → 批次永停 processing（A3/D-110）。
        List<String> cells = new ArrayList<>(PROPS.columns().headerOrder());
        cells.set(0, "あ".repeat(30_000));
        String message = parser.headerMismatch(cells);
        assertThat(message).contains("1列目").contains("管理番号").endsWith("…」になっています");
        assertThat(message).hasSizeLessThan(500);
    }

    @Test
    void parse_shortRow_reportsPreciseMissingField() {
        // 12 列で打ち切り → 13 列目（備考）以降は空扱い、必須列だけがエラーになる
        List<String> cells = List.of("HT9-A5X", "HT", "2026-09-01", "1000",
                "", "", "", "1", "", "", "", "");
        ParseOutcome outcome = parser.parse(cells);
        // この 12 列までで必須は揃っているため Ok（残りは null）
        assertThat(outcome).isInstanceOf(ParseOutcome.Ok.class);
        ParsedRow parsed = ((ParseOutcome.Ok) outcome).row();
        assertThat(parsed.remark()).isNull();
        assertThat(parsed.itemName()).isNull();
    }
}
