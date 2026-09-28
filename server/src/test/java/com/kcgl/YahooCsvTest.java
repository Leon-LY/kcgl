package com.kcgl;

import com.kcgl.common.web.BizException;
import com.kcgl.module.itemcode.ItemCodeFormatter;
import com.kcgl.module.yahoo.YahooCsvDecoder;
import com.kcgl.module.yahoo.YahooMergePolicy;
import com.kcgl.module.yahoo.YahooProperties;
import com.kcgl.module.yahoo.YahooRowParser;
import com.kcgl.module.yahoo.YahooRowParser.ListingStatus;
import com.kcgl.module.yahoo.YahooRowParser.ParseOutcome;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 雅虎 CSV 三件套单元测试（M4-①②，docs/01 7.4）：
 * - 编码检测链：BOM 优先 → chardet 提示序 → 严格解码验证，全败抛错；
 *   MS932 必须吃下 NEC/IBM 扩展字（Shift_JIS 严格解码的经典坑）
 * - 行清洗：全角 NFKC 归一、￥/千分位剥离、状态文本映射、双位日期、
 *   管理号正则校验（不合法=未匹配行保留原文码，不丢行）
 * - 合并策略：状态单调只前进（成交禁回退、取消→在售例外）、价格按
 *   事件时间 recency（同刻=后批胜）——陈旧 CSV 重传不回退
 */
class YahooCsvTest {

    YahooProperties props;
    YahooRowParser parser;

    @BeforeEach
    void setUp() {
        props = YahooProperties.defaults();
        parser = new YahooRowParser(props);
    }

    // ------------------------------------------------------------- 编码检测链

    @Test
    void decode_utf8Bom_detectedAndStripped() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "商品コード,状態\nHTK9-A1X,出品中".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        System.arraycopy(body, 0, bytes, bom.length, body.length);

        YahooCsvDecoder.Decoded decoded = YahooCsvDecoder.decode(bytes);

        assertThat(decoded.encoding()).isEqualTo("UTF-8");
        assertThat(decoded.text()).startsWith("商品コード");
    }

    @Test
    void decode_ms932WithNecChars_decodedViaMs932() {
        // ①㈱℡＝NEC/IBM 扩展区：Shift_JIS 名义字节但仅 MS932 完整覆盖
        String source = "商品コード,①㈱℡,状態\nHTK9-A1X,メモ①,出品中";
        byte[] bytes = source.getBytes(Charset.forName("MS932"));

        YahooCsvDecoder.Decoded decoded = YahooCsvDecoder.decode(bytes);

        assertThat(decoded.text()).isEqualTo(source);
        assertThat(decoded.encoding()).isEqualTo("MS932");
    }

    @Test
    void decode_utf8NoBom_detected() {
        String source = "商品コード,状態\nHTK9-A1X,出品中";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);

        YahooCsvDecoder.Decoded decoded = YahooCsvDecoder.decode(bytes);

        assertThat(decoded.text()).isEqualTo(source);
        assertThat(decoded.encoding()).isEqualTo("UTF-8");
    }

    @Test
    void decode_eucjp_decoded() {
        String source = "商品コード,状態\nHTK9-A1X,出品中";
        byte[] bytes = source.getBytes(Charset.forName("EUC-JP"));

        YahooCsvDecoder.Decoded decoded = YahooCsvDecoder.decode(bytes);

        assertThat(decoded.text()).isEqualTo(source);
        assertThat(decoded.encoding()).isEqualTo("EUC-JP");
    }

    @Test
    void decode_binaryGarbage_allCandidatesFail_throws() {
        // 0xFF 在 UTF-8/MS932/EUC-JP 全部非法（严格解码 REPORT）
        byte[] bytes = new byte[64];
        java.util.Arrays.fill(bytes, (byte) 0xFF);

        assertThatThrownBy(() -> YahooCsvDecoder.decode(bytes))
                .isInstanceOf(BizException.class);
    }

    // ------------------------------------------------------------- 行清洗

    private CSVRecord recordOf(String... cells) throws Exception {
        // 含逗号/引号的值必须走 CSV 引号转义——否则列移位测的不是解析器是夹具
        StringBuilder line = new StringBuilder();
        for (String cell : cells) {
            if (line.length() > 0) {
                line.append(',');
            }
            if (cell.contains(",") || cell.contains("\"")) {
                line.append('"').append(cell.replace("\"", "\"\"")).append('"');
            } else {
                line.append(cell);
            }
        }
        List<CSVRecord> records = parser.csvFormat().parse(new StringReader(
                String.join(",", props.columns().headerOrder()) + "\n" + line + "\n"))
                .getRecords();
        return records.get(0);
    }

    @Test
    void parse_fullWidthCodeAndPrice_normalized() throws Exception {
        CSVRecord record = recordOf("a12345", "ＨＴＫ９－Ａ１Ｘ", "￥１５，０００", "", "出品中",
                "2026/9/28 14:30", "");

        ParseOutcome outcome = parser.parse(record);

        assertThat(outcome).isInstanceOf(ParseOutcome.Ok.class);
        YahooRowParser.ParsedRow row = ((ParseOutcome.Ok) outcome).row();
        assertThat(row.itemCode()).isEqualTo("HTK9-A1X");
        assertThat(row.rawItemCode()).isEqualTo("ＨＴＫ９－Ａ１Ｘ");
        assertThat(row.listPrice()).isEqualTo(15000L);
        assertThat(row.soldPrice()).isNull();
        assertThat(row.status()).isEqualTo(ListingStatus.ON_SALE);
        assertThat(row.listedAt()).isEqualTo(LocalDateTime.of(2026, 9, 28, 14, 30));
    }

    @Test
    void parse_priceYenAndCommas_stripped() throws Exception {
        CSVRecord record = recordOf("a12345", "HTK9-A1X", "¥3,500円", "12,000", "落札されました",
                "2026/9/1 10:00", "2026/9/7 21:05");

        ParseOutcome outcome = parser.parse(record);

        assertThat(outcome).isInstanceOf(ParseOutcome.Ok.class);
        YahooRowParser.ParsedRow row = ((ParseOutcome.Ok) outcome).row();
        assertThat(row.listPrice()).isEqualTo(3500L);
        assertThat(row.soldPrice()).isEqualTo(12000L);
        assertThat(row.status()).isEqualTo(ListingStatus.SOLD);
        assertThat(row.closedAt()).isEqualTo(LocalDateTime.of(2026, 9, 7, 21, 5, 0));
    }

    @Test
    void parse_statusMapping_containsOrder_canceledBeatsSold() throws Exception {
        // 落札されませんでした 含「落札」但不构成成交——取消判定先于成交
        assertThat(((ParseOutcome.Ok) parser.parse(
                recordOf("a1", "HTK9-A1X", "", "", "落札されませんでした", "", ""))).row().status())
                .isEqualTo(ListingStatus.CANCELED);
        assertThat(((ParseOutcome.Ok) parser.parse(
                recordOf("a1", "HTK9-A1X", "", "", "取消", "", ""))).row().status())
                .isEqualTo(ListingStatus.CANCELED);
        assertThat(((ParseOutcome.Ok) parser.parse(
                recordOf("a1", "HTK9-A1X", "", "", "出品中", "", ""))).row().status())
                .isEqualTo(ListingStatus.ON_SALE);
    }

    @Test
    void parse_invalidItemCode_keptAsUnmatchedWithRawCode() throws Exception {
        CSVRecord record = recordOf("a12345", "NOT-A-CODE", "", "", "出品中", "", "");

        ParseOutcome outcome = parser.parse(record);

        assertThat(outcome).isInstanceOf(ParseOutcome.Ok.class);
        YahooRowParser.ParsedRow row = ((ParseOutcome.Ok) outcome).row();
        assertThat(row.itemCode()).isNull();
        assertThat(row.rawItemCode()).isEqualTo("NOT-A-CODE");
    }

    @Test
    void parse_missingAuctionId_errorRow() throws Exception {
        ParseOutcome outcome = parser.parse(recordOf("", "HTK9-A1X", "", "", "出品中", "", ""));

        assertThat(outcome).isInstanceOf(ParseOutcome.Err.class);
    }

    @Test
    void parse_unknownStatus_errorRow() throws Exception {
        ParseOutcome outcome = parser.parse(recordOf("a1", "HTK9-A1X", "", "", "謎の状態", "", ""));

        assertThat(outcome).isInstanceOf(ParseOutcome.Err.class);
    }

    @Test
    void parse_badPrice_errorRow() throws Exception {
        ParseOutcome outcome = parser.parse(recordOf("a1", "HTK9-A1X", "三百円", "", "出品中", "", ""));

        assertThat(outcome).isInstanceOf(ParseOutcome.Err.class);
    }

    @Test
    void parse_priceOverSystemLimit_errorRow() throws Exception {
        ParseOutcome outcome = parser.parse(recordOf("a1", "HTK9-A1X", "100000000", "", "出品中", "", ""));

        assertThat(outcome).isInstanceOf(ParseOutcome.Err.class);
    }

    @Test
    void parse_badDate_errorRow() throws Exception {
        ParseOutcome outcome = parser.parse(recordOf("a1", "HTK9-A1X", "", "", "出品中", "2026/13/45 99:99", ""));

        assertThat(outcome).isInstanceOf(ParseOutcome.Err.class);
    }

    // ------------------------------------------------------------- 合并策略

    @Test
    void mergeStatus_forwardTransitionsApplied() {
        assertThat(YahooMergePolicy.clampStatus(1, 2)).isEqualTo(2);
        assertThat(YahooMergePolicy.clampStatus(1, 3)).isEqualTo(3);
        assertThat(YahooMergePolicy.clampStatus(3, 1)).isEqualTo(1); // 重新出品
        assertThat(YahooMergePolicy.clampStatus(1, 1)).isEqualTo(1);
    }

    @Test
    void mergeStatus_soldIsTerminal_noRegression() {
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
        assertThat(ItemCodeFormatter.matches("HTK9-A1X")).isTrue();
        assertThat(ItemCodeFormatter.matches("HTK12-BB10X")).isTrue();
        assertThat(ItemCodeFormatter.matches("HTK9-A1")).isTrue(); // 不含价格码模式
        assertThat(ItemCodeFormatter.matches("NOT-A-CODE")).isFalse();
        assertThat(ItemCodeFormatter.matches("htk9-a1x")).isFalse();
    }
}
