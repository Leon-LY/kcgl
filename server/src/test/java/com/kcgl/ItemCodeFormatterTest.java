package com.kcgl;

import com.kcgl.module.itemcode.ItemCodeFormatter;
import com.kcgl.module.itemcode.ItemCodeFormatter.ParsedCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理号纯逻辑单测（docs/01 7.1；D-068 去年代号；D-165 会场放宽+月补零）：
 * 组号/月补零恒2位/变长会场/进位/含价格码双态/拆装往返/旧格式拒绝。
 */
class ItemCodeFormatterTest {

    @ParameterizedTest
    @CsvSource({
            "HT, 1,  A, 1,  X, HT01-A1X",
            "HT, 9,  A, 1,  X, HT09-A1X",
            "HT, 10, A, 12, X, HT10-A12X",
            "HT, 12, A, 99, X, HT12-A99X",
            "NG, 12, AA, 5,  Q, NG12-AA5Q",
            "FK, 2,  ZZ, 9,  B, FK02-ZZ9B",
            // 变长会场（D-165）：单字符、含数字、满 8 位
            "A,  9,  A, 1,  X, A09-A1X",
            "T1, 9,  A, 1,  X, T109-A1X",
            "NAGOYA2, 12, A, 1, X, NAGOYA212-A1X",
    })
    void format_withPriceCode_producesExpectedCode(String venue, int month, String prefix, int seq,
            String band, String expected) {
        assertThat(ItemCodeFormatter.format(venue, month, prefix, seq, band, true))
                .isEqualTo(expected);
    }

    @Test
    void format_withoutPriceCode_omitsBandSuffix() {
        assertThat(ItemCodeFormatter.format("HT", 9, "A", 1, "X", false))
                .isEqualTo("HT09-A1");
    }

    @ParameterizedTest
    @CsvSource({
            "A, B", "B, C", "Y, Z", "Z, AA", "AA, AB", "AZ, BA",
            "BA, BB", "ZZ, AAA", "AAA, AAB", "ZZA, ZZB", "ZZZ, AAAA",
    })
    void nextPrefix_base26_advancesWithCarry(String current, String expected) {
        assertThat(ItemCodeFormatter.nextPrefix(current)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HT09-A1X", "HT01-A1X", "HT10-A12X", "HT12-A99X",
            "NG12-AA5Q", "FK02-ZZ9B", "HT09-A1", "HT09-AA99",
            "A09-A1X", "T109-A1X", "NAGOYA212-A1X"})
    void parseFormat_roundTrip_withAndWithoutPriceCode(String code) {
        ParsedCode parsed = ItemCodeFormatter.parse(code);
        String band = parsed.bandCode();
        boolean includePrice = band != null;
        assertThat(ItemCodeFormatter.format(parsed.venueCode(), parsed.month(),
                parsed.prefix(), parsed.seq(), band == null ? "X" : band, includePrice))
                .isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ht09-A1X", "HT00-A1X", "HT13-A1X", "HT09-A0X", "HT09-A100X",
            "HT09-A1XY", "HT09A1X", "HT09-A01", "",
            // D-165 回归：月不补零的旧（D-068）格式必须拒绝，防新旧混录
            "HT9-A1X", "HT1-A1X", "HT9-A1"})
    void parse_invalidCode_throwsIllegalArgumentException(String code) {
        assertThatThrownBy(() -> ItemCodeFormatter.parse(code))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 旧三段格式（会场2字母+年代号+月，如 HTA9-A1X）已退役，解析必须拒绝防新旧混录。
     * 注意：会场放宽为 1-8 位后，「会场+数字」形式本身合法，故只保留不会与新格式
     * 撞形的旧例（NGA12-AA5Q 现读作会场 NGA+月12，已不再是「必须拒绝」的样本）。
     */
    @ParameterizedTest
    @ValueSource(strings = {"HTA9-A1X", "HTB5-A2X"})
    void parse_legacyYearCodeFormat_rejected(String code) {
        assertThat(ItemCodeFormatter.matches(code)).isFalse();
        assertThatThrownBy(() -> ItemCodeFormatter.parse(code))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "A, 1", "B, 2", "Y, 25", "Z, 26", "AA, 27", "AZ, 52",
            "BA, 53", "ZZ, 702", "AAA, 703", "ZZZ, 18278",
    })
    void prefixRank_knownPrefixes_returnsBigEndianPosition(String prefix, int expected) {
        assertThat(ItemCodeFormatter.prefixRank(prefix)).isEqualTo(expected);
    }

    /** 红证 String.compareTo 方向错误：字典序 Z＞AA，与进位序 Z＜AA 相反（D-058 D）。 */
    @Test
    void prefixRank_zVersusAa_oppositeOfStringCompare() {
        assertThat("Z".compareTo("AA")).isPositive();
        assertThat(ItemCodeFormatter.prefixRank("Z"))
                .isLessThan(ItemCodeFormatter.prefixRank("AA"));
    }

    /** 权值与进位链互恰：nextPrefix 恒 +1（导入计数器跳变判定的正确性根基）。 */
    @ParameterizedTest
    @ValueSource(strings = {"A", "Z", "AA", "AZ", "BA", "ZZ", "AAA", "ZZA", "ZZZ"})
    void prefixRank_nextPrefixChain_incrementsByOne(String prefix) {
        assertThat(ItemCodeFormatter.prefixRank(ItemCodeFormatter.nextPrefix(prefix)))
                .isEqualTo(ItemCodeFormatter.prefixRank(prefix) + 1);
    }
}
