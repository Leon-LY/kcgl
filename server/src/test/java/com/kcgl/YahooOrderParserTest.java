package com.kcgl;

import cn.idev.excel.FastExcel;
import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.event.AnalysisEventListener;
import com.kcgl.module.itemcode.ItemCodeFormatter;
import com.kcgl.module.yahoo.YahooMergePolicy;
import com.kcgl.module.yahoo.YahooOrderParser;
import com.kcgl.module.yahoo.YahooOrderParser.ParsedRow;
import com.kcgl.module.yahoo.YahooOrderParser.RowOutcome;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 受注 xlsx 行清洗单元测试（M5-②b，D-069，docs/01 7.4）：
 * - 列映射钉死：A/B/C/D/P 表头契约；表头外列（甲方手工工作列）全忽略
 * - 序列日期（样张原生形态）→ JST LocalDateTime；日期对象/文本兜底
 * - まとめ売り：B 列换行分隔多码 → 一行拆 N 子行、soldPrice 留空（手填后补）
 * - 自码归一：trim+大写+NFKC+新码形校验（不合法=保留原文码作未匹配行，不丢行）
 * - 合并策略：状态单调只前进（成交禁回退）、价格按事件时间 recency（同刻=后批胜）
 * - 真实样张契约锁定：docs/ストア9.20(1).xlsx（A4 交付物）全量解析
 */
class YahooOrderParserTest {

    private static final LocalDateTime SEP17_1002 = LocalDateTime.of(2026, 9, 17, 10, 2, 13);

    // ------------------------------------------------------------- 夹具

    /** 单行单元格（0 基索引）；仅放消费列。 */
    private static Map<Integer, Object> row(Object... colValuePairs) {
        Map<Integer, Object> cells = new HashMap<>();
        for (int i = 0; i < colValuePairs.length; i += 2) {
            cells.put((Integer) colValuePairs[i], colValuePairs[i + 1]);
        }
        return cells;
    }

    private static Map<Integer, Object> header() {
        return row(0, "OrderId", 1, "YahooAuctionMerchantId", 2, "OrderTime",
                3, "YahooAuctionId", 15, "UnitPrice");
    }

    // ------------------------------------------------------------- 表头契约

    @Test
    void header_exactConsumedColumns_noMismatch() {
        assertThat(YahooOrderParser.headerMismatch(header())).isNull();
    }

    @Test
    void header_wrongColumnAtConsumedPosition_reported() {
        Map<Integer, Object> bad = header();
        bad.put(1, "商品コード"); // 误传出品状態表
        bad.put(15, "Total");    // P 列错位
        String mismatch = YahooOrderParser.headerMismatch(bad).text();
        assertThat(mismatch).contains("B列").contains("YahooAuctionMerchantId")
                .contains("P列").contains("UnitPrice");
    }

    // ------------------------------------------------------------- 行清洗

    @Test
    void parse_singleCodeRow_fullFacts() {
        RowOutcome outcome = YahooOrderParser.parse(row(
                0, 10004866d, 1, "M-A8-F9", 2, 46282.418206018519,
                3, "b1243949032", 15, 11273d));

        assertThat(outcome).isInstanceOf(RowOutcome.Ok.class);
        List<ParsedRow> rows = ((RowOutcome.Ok) outcome).rows();
        assertThat(rows).hasSize(1);
        ParsedRow parsed = rows.get(0);
        assertThat(parsed.orderId()).isEqualTo("10004866");
        assertThat(parsed.auctionId()).isEqualTo("b1243949032");
        assertThat(parsed.rawItemCode()).isEqualTo("M-A8-F9");
        assertThat(parsed.itemCode()).isNull(); // 甲方旧码≠新码形=未匹配行
        assertThat(parsed.soldPrice()).isEqualTo(11273L);
        assertThat(parsed.orderTime()).isEqualTo(SEP17_1002);
        assertThat(parsed.multiItem()).isFalse();
    }

    @Test
    void parse_multiCodeOrder_splitsPerItem_soldPriceLeftBlank() {
        RowOutcome outcome = YahooOrderParser.parse(row(
                0, "10004900", 1, "HT9-A1X\nHT9-A2X", 2, 46283.5,
                3, "x1243954505", 15, 12595d));

        assertThat(outcome).isInstanceOf(RowOutcome.Ok.class);
        List<ParsedRow> rows = ((RowOutcome.Ok) outcome).rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).itemCode()).isEqualTo("HT9-A1X");
        assertThat(rows.get(1).itemCode()).isEqualTo("HT9-A2X");
        assertThat(rows).allSatisfy(parsed -> {
            assertThat(parsed.multiItem()).isTrue();
            assertThat(parsed.soldPrice()).isNull(); // 合计价无拆分依据，手填后补
            assertThat(parsed.auctionId()).isEqualTo("x1243954505");
            assertThat(parsed.orderId()).isEqualTo("10004900");
        });
    }

    @Test
    void parse_mixedCaseCode_normalizedToUpper() {
        RowOutcome outcome = YahooOrderParser.parse(row(
                1, "ae4e-sr1", 3, "e1", 2, 46283d, 15, 1000d));

        List<ParsedRow> rows = ((RowOutcome.Ok) outcome).rows();
        assertThat(rows.get(0).rawItemCode()).isEqualTo("ae4e-sr1");
        assertThat(rows.get(0).itemCode()).isNull(); // 大写后仍非新码形（月位后须连字符）
    }

    @Test
    void parse_fullWidthNewCode_normalizedAndMatched() {
        RowOutcome outcome = YahooOrderParser.parse(row(
                1, "ｈｔ９－ａ１Ｘ", 3, "e1", 2, 46283d, 15, "￥12,000"));

        List<ParsedRow> rows = ((RowOutcome.Ok) outcome).rows();
        assertThat(rows.get(0).itemCode()).isEqualTo("HT9-A1X");
        assertThat(rows.get(0).soldPrice()).isEqualTo(12000L);
    }

    @Test
    void parse_emptyCodeColumn_singlePlaceholderRow() {
        RowOutcome outcome = YahooOrderParser.parse(row(
                1, "", 3, "e1", 2, 46283d, 15, 1000d));

        List<ParsedRow> rows = ((RowOutcome.Ok) outcome).rows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).rawItemCode()).isNull();
        assertThat(rows.get(0).itemCode()).isNull();
        assertThat(rows.get(0).soldPrice()).isEqualTo(1000L); // 单码语义：价即本件
    }

    @Test
    void parse_priceTextWithYenAndCommas_stripped() {
        RowOutcome outcome = YahooOrderParser.parse(row(
                1, "HT9-A1X", 3, "e1", 2, 46283d, 15, "¥3,500円"));

        assertThat(((RowOutcome.Ok) outcome).rows().get(0).soldPrice()).isEqualTo(3500L);
    }

    @Test
    void parse_orderTime_textSerialAndLocalDateTime_paths() {
        RowOutcome serialText = YahooOrderParser.parse(row(3, "e1", 2, "46282.5", 15, 1d));
        assertThat(((RowOutcome.Ok) serialText).rows().get(0).orderTime())
                .isEqualTo(LocalDateTime.of(2026, 9, 17, 12, 0));

        RowOutcome dateTime = YahooOrderParser.parse(row(3, "e1",
                2, LocalDateTime.of(2026, 9, 18, 8, 30), 15, 1d));
        assertThat(((RowOutcome.Ok) dateTime).rows().get(0).orderTime())
                .isEqualTo(LocalDateTime.of(2026, 9, 18, 8, 30));

        // Date 对象按 POI 约定=墙钟装载在 UTC（构造即按 UTC 钉死，与 JVM 时区无关）
        RowOutcome dateObject = YahooOrderParser.parse(row(3, "e1",
                2, java.sql.Timestamp.from(LocalDateTime.of(2026, 9, 18, 8, 30)
                        .toInstant(java.time.ZoneOffset.UTC)), 15, 1d));
        assertThat(((RowOutcome.Ok) dateObject).rows().get(0).orderTime())
                .isEqualTo(LocalDateTime.of(2026, 9, 18, 8, 30));
    }

    @Test
    void parse_structuralFailures_errorRow() {
        assertThat(YahooOrderParser.parse(row(1, "HT9-A1X", 2, 46283d))) // 拍品 ID 空
                .isInstanceOf(RowOutcome.Err.class);
        assertThat(YahooOrderParser.parse(row(1, "HT9-A1X", 3, "e1"))) // 时刻空
                .isInstanceOf(RowOutcome.Err.class);
        assertThat(YahooOrderParser.parse(row(1, "HT9-A1X", 3, "e1", 2, "昨日"))) // 时刻不可解
                .isInstanceOf(RowOutcome.Err.class);
        assertThat(YahooOrderParser.parse(row(1, "HT9-A1X", 3, "e1", 2, 46283d, 15, "三百円")))
                .isInstanceOf(RowOutcome.Err.class);
        assertThat(YahooOrderParser.parse(row(1, "HT9-A1X", 3, "e1", 2, 46283d, 15, 100000000d)))
                .isInstanceOf(RowOutcome.Err.class);
        assertThat(YahooOrderParser.parse(row(1, "HT9-A1X", 3, "e1", 2, 46283d, 15, 12.5))) // 非整数金额
                .isInstanceOf(RowOutcome.Err.class);
    }

    // ------------------------------------------------------------- 合并策略（沿用 M4 纪律）

    @Test
    void mergeStatus_soldIsTerminal_noRegression() {
        assertThat(YahooMergePolicy.clampStatus(1, 2)).isEqualTo(2);
        assertThat(YahooMergePolicy.clampStatus(3, 2)).isEqualTo(2); // 受注=成交事实集
        assertThat(YahooMergePolicy.clampStatus(2, 1)).isEqualTo(2);
        assertThat(YahooMergePolicy.clampStatus(2, 3)).isEqualTo(2);
    }

    @Test
    void mergePriceRecency_newerWins_sameTimeLaterBatchWins() {
        LocalDateTime t1 = LocalDateTime.of(2026, 9, 1, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 9, 8, 10, 0);
        assertThat(YahooMergePolicy.incomingIsNewer(t1, t2)).isTrue();
        assertThat(YahooMergePolicy.incomingIsNewer(t2, t1)).isFalse();
        assertThat(YahooMergePolicy.incomingIsNewer(t1, t1)).isTrue(); // 同刻=后批胜
        assertThat(YahooMergePolicy.incomingIsNewer(t1, null)).isFalse(); // 无时间戳=最旧
        assertThat(YahooMergePolicy.incomingIsNewer(null, t1)).isTrue(); // 首见
    }

    @Test
    void itemCodePattern_validCodes() {
        assertThat(ItemCodeFormatter.matches("HT9-A1X")).isTrue();
        assertThat(ItemCodeFormatter.matches("HT12-BB10X")).isTrue();
        assertThat(ItemCodeFormatter.matches("HT9-A1")).isTrue(); // 不含价格码模式
        assertThat(ItemCodeFormatter.matches("M-A8-F9")).isFalse(); // 甲方旧码
        assertThat(ItemCodeFormatter.matches("htk9-a1x")).isFalse();
    }

    // ------------------------------------------------------------- 真实样张契约锁定

    /**
     * 已交付样张（docs/ストア9.20(1).xlsx 与 (2).xlsx，A4 交付物）全量解析：
     * 43 数据行、2 まとめ売り、45 唯一自码、时间窗 2026-09-17→09-24、
     * W/X/Y/Z 手工工作列忽略不炸。两批样张同形，断言对任一份都成立。
     *
     * <p>样张是甲方业务数据、docs/ 不入版本库，故**取不到就跳过**（assumeTrue）：
     * 这是本机对真文件的契约锁，不是 CI 门禁（CI 上恒跳过）。只认 (1) 会让这里
     * 在只有 (2) 的机器上静默跳过——"锁"看起来在、实际从不跑，比没有更坏。
     */
    private static final List<String> SAMPLE_FILES =
            List.of("ストア9.20(1).xlsx", "ストア9.20(2).xlsx");

    private static Path sampleFile() {
        for (String name : SAMPLE_FILES) {
            Path path = Path.of("..", "docs", name);
            if (Files.exists(path)) {
                return path;
            }
        }
        return null;
    }

    @Test
    void sampleFile_realOrderExport_parsedEndToEnd() throws IOException {
        Path sample = sampleFile();
        assumeTrue(sample != null, "样张未入库，跳过契约锁定");

        List<Map<Integer, Object>> rows = readRows(Files.readAllBytes(sample));
        assertThat(rows).hasSize(44); // 表头 + 43 数据行
        assertThat(YahooOrderParser.headerMismatch(rows.get(0))).isNull();

        List<ParsedRow> parsed = new ArrayList<>();
        int multiRows = 0;
        for (Map<Integer, Object> cells : rows.subList(1, rows.size())) {
            RowOutcome outcome = YahooOrderParser.parse(cells);
            assertThat(outcome).as("样张数据行应全部可解析").isInstanceOf(RowOutcome.Ok.class);
            List<ParsedRow> subRows = ((RowOutcome.Ok) outcome).rows();
            if (subRows.size() > 1) {
                multiRows++;
            }
            parsed.addAll(subRows);
        }
        assertThat(multiRows).isEqualTo(2);
        assertThat(parsed).hasSize(45); // 43 行 + まとめ売り各多 1 子行
        assertThat(parsed.stream().map(ParsedRow::rawItemCode).distinct()).hasSize(45);
        assertThat(parsed).allSatisfy(p -> {
            assertThat(p.orderTime()).isAfter(LocalDateTime.of(2026, 9, 16, 23, 59));
            assertThat(p.orderTime()).isBefore(LocalDateTime.of(2026, 9, 25, 0, 0));
        });
        // 首数据行（样张实测）：旧码未匹配 + 全角无关的原文保留
        ParsedRow first = parsed.get(0);
        assertThat(first.orderId()).isEqualTo("10004866");
        assertThat(first.auctionId()).isEqualTo("b1243949032");
        assertThat(first.rawItemCode()).isEqualTo("M-A8-F9");
        assertThat(first.itemCode()).isNull();
        assertThat(first.soldPrice()).isEqualTo(11273L);
        assertThat(first.orderTime()).isEqualTo(LocalDateTime.of(2026, 9, 17, 10, 2)); // 显示文本=分精度
    }

    // ------------------------------------------------------------- 防滥用上限（安全评审加固）

    @Test
    void parse_codesPerRowCapped_subRowFanOutBounded() {
        // 上限内（32 码）：まとめ売り照常一码一子行
        RowOutcome ok = YahooOrderParser.parse(row(
                0, 10004866d, 1, String.join("\n", Collections.nCopies(32, "HT9-A1X")),
                2, 46282.418206018519, 3, "b1243949032", 15, 11273d));
        assertThat(((RowOutcome.Ok) ok).rows()).hasSize(32);

        // 超限（33 码）→ 行错误：B 格 ≤32767 字可载数千码，无上限则单行可放大出
        // 上万子行事务占死单线程管线（安全评审 MEDIUM-2）
        RowOutcome err = YahooOrderParser.parse(row(
                0, 10004866d, 1, String.join("\n", Collections.nCopies(33, "HT9-A1X")),
                2, 46282.418206018519, 3, "b1243949032", 15, 11273d));
        assertThat(err).isInstanceOf(RowOutcome.Err.class);
        assertThat(((RowOutcome.Err) err).reason()).contains("32");
    }

    @Test
    void parse_overlongCode_rowError_rawItemCodeColumnWidthGuard() {
        // B 列码同 raw_item_code VARCHAR(32) 口径：超宽码=行错误（此前落到 DB 层
        // 才失败，降级成泛化错误行），而非整行丢失判定含糊
        RowOutcome outcome = YahooOrderParser.parse(row(
                0, 10004866d, 1, "X".repeat(33), 2, 46282.418206018519,
                3, "b1243949032", 15, 11273d));
        assertThat(outcome).isInstanceOf(RowOutcome.Err.class);
        assertThat(((RowOutcome.Err) outcome).reason()).contains("商品コード");
    }

    @Test
    void parse_unparseableCell_reasonTruncated_notFullCellText() {
        // xlsx 单格最大 32767 字——reason 只许带有限前缀，否则错误采样随行数无界
        // 累积（zip 容器把该面放大 ~100 倍，安全评审 MEDIUM-1）
        String garbage = "不".repeat(32_767);
        RowOutcome timeErr = YahooOrderParser.parse(row(
                0, 10004866d, 1, "HT9-A1X", 2, garbage, 3, "b1243949032", 15, 11273d));
        assertThat(((RowOutcome.Err) timeErr).reason()).hasSizeLessThanOrEqualTo(100);

        RowOutcome priceErr = YahooOrderParser.parse(row(
                0, 10004866d, 1, "HT9-A1X", 2, 46282.418206018519, 3, "b1243949032", 15, garbage));
        assertThat(((RowOutcome.Err) priceErr).reason()).hasSizeLessThanOrEqualTo(100);
    }

    @Test
    void parse_negativePriceText_rowError_sameAsNumberBranch() {
        // 文本分支与 Number 分支同口径拒负价（此前 "-500" 文本格可穿过——代码评审 MEDIUM-3）
        RowOutcome outcome = YahooOrderParser.parse(row(
                0, 10004866d, 1, "HT9-A1X", 2, 46282.418206018519,
                3, "b1243949032", 15, "-500"));
        assertThat(outcome).isInstanceOf(RowOutcome.Err.class);
        assertThat(((RowOutcome.Err) outcome).reason()).contains("落札価格が不正です");
    }

    /** FastExcel 读首个工作表全部行（headRowNumber(0)=表头行也进回调）。 */
    private static List<Map<Integer, Object>> readRows(byte[] bytes) throws IOException {
        List<Map<Integer, Object>> rows = new ArrayList<>();
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            FastExcel.read(in, new AnalysisEventListener<Map<Integer, Object>>() {
                @Override
                public void invoke(Map<Integer, Object> row, AnalysisContext context) {
                    rows.add(row);
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    // 全部行已收集
                }
            }).sheet(0).headRowNumber(0).doRead();
        }
        return rows;
    }
}
