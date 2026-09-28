package com.kcgl.common.util;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 管理号容错归一唯一出口（docs/01 7.8）：trim + NFKC（全角→半角）+ 大写。
 * 扫码定位/搜索/CSV 清洗共用——散落各处的等价实现必然漂移。
 */
public final class CodeNormalizer {

    private CodeNormalizer() {
    }

    public static String normalize(String code) {
        return Normalizer.normalize(code.trim(), Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
    }
}
