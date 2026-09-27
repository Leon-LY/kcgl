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
    void 组装_含价格码(String venue, String yearCode, int month, String prefix, int seq,
            String band, String expected) {
        assertThat(ItemCodeFormatter.format(venue, yearCode, month, prefix, seq, band, true))
                .isEqualTo(expected);
    }

    @Test
    void 组装_不含价格码_省略末位() {
        assertThat(ItemCodeFormatter.format("HT", "K", 9, "A", 1, "X", false))
                .isEqualTo("HTK9-A1");
    }

    @ParameterizedTest
    @CsvSource({
            "A, B", "B, C", "Y, Z", "Z, AA", "AA, AB", "AZ, BA",
            "BA, BB", "ZZ, AAA", "AAA, AAB", "ZZA, ZZB", "ZZZ, AAAA",
    })
    void 前缀进位_base26(String current, String expected) {
        assertThat(ItemCodeFormatter.nextPrefix(current)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTK9-A1X", "HTK1-A1X", "HTK10-A12X", "HTK12-A99X",
            "NGA12-AA5Q", "FKZ2-ZZ9B", "HTK9-A1", "HTK9-AA99"})
    void 拆装往返_含价格码与不含双态(String code) {
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
    void 非法管理号_拒绝(String code) {
        assertThatThrownBy(() -> ItemCodeFormatter.parse(code))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
