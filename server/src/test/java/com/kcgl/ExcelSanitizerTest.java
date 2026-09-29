package com.kcgl;

import com.kcgl.module.excel.ExcelSanitizer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导出公式注入消毒单测（docs/01 八节，D-058 F）：危险前缀前置单引号，普通文本
 * 与 null/空串原样通过。
 */
class ExcelSanitizerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "=cmd|'/C calc'",
            "+1+1",
            "-2+3",
            "@SUM(A1:A9)",
            "\t=HYPERLINK(\"http://evil\")",
    })
    void sanitize_dangerousPrefix_prependsSingleQuote(String value) {
        assertThat(ExcelSanitizer.sanitize(value)).isEqualTo("'" + value);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "HT9-A1X",
            "備考テキスト（１行目）",
            "1,000円",
            "'already-quoted",
            "A+B 計算ではないテキスト",
    })
    void sanitize_plainText_returnsUnchanged(String value) {
        assertThat(ExcelSanitizer.sanitize(value)).isEqualTo(value);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void sanitize_nullOrEmpty_passesThrough(String value) {
        assertThat(ExcelSanitizer.sanitize(value)).isEqualTo(value);
    }

    @Test
    void sanitize_dangerousCharNotAtStart_returnsUnchanged() {
        // 危険なのは「先頭文字」のみ——文中の = や @ はただのテキスト
        assertThat(ExcelSanitizer.sanitize("A=B")).isEqualTo("A=B");
        assertThat(ExcelSanitizer.sanitize("メール user@example.com")).isEqualTo("メール user@example.com");
    }
}
