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
 * 管理号纯逻辑单测（docs/01 7.1）：组号/月不补零/进位/含价格码双态/拆装往返。
 */
class ItemCodeFormatterTest {

    @ParameterizedTest
    @CsvSource({
            "HT, K, 1,  A, 1,  X, HTK1-A1X",
            "HT, K, 9,  A, 1,  X, HTK9-A1X",
            "HT, K, 10, A, 12, X, HTK10-A12X",
            "HT, K, 12, A, 99, X, HTK12-A99X",
            "NG, A, 12, AA, 5,  Q, NGA12-AA5Q",
            "FK, Z, 2,  ZZ, 9,  B, FKZ2-ZZ9B",
    })
    void format_withPriceCode_producesExpectedCode(String venue, String yearCode, int month, String prefix, int seq,
            String band, String expected) {
        assertThat(ItemCodeFormatter.format(venue, yearCode, month, prefix, seq, band, true))
                .isEqualTo(expected);
    }

    @Test
    void format_withoutPriceCode_omitsBandSuffix() {
        assertThat(ItemCodeFormatter.format("HT", "K", 9, "A", 1, "X", false))
                .isEqualTo("HTK9-A1");
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
    @ValueSource(strings = {"HTK9-A1X", "HTK1-A1X", "HTK10-A12X", "HTK12-A99X",
            "NGA12-AA5Q", "FKZ2-ZZ9B", "HTK9-A1", "HTK9-AA99"})
    void parseFormat_roundTrip_withAndWithoutPriceCode(String code) {
        ParsedCode parsed = ItemCodeFormatter.parse(code);
        String band = parsed.bandCode();
        boolean includePrice = band != null;
        assertThat(ItemCodeFormatter.format(parsed.venueCode(), parsed.yearCode(), parsed.month(),
                parsed.prefix(), parsed.seq(), band == null ? "X" : band, includePrice))
                .isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"htk9-A1X", "HTK0-A1X", "HTK13-A1X", "HTK9-A0X", "HTK9-A100X",
            "HTK9-A1XY", "HTK9A1X", "HTKX9-A1X", "", "HTK9-A01"})
    void parse_invalidCode_throwsIllegalArgumentException(String code) {
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
