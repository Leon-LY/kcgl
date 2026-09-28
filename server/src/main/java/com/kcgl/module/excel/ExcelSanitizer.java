package com.kcgl.module.excel;

/**
 * 导出公式注入消毒（docs/01 八节上传/导出；D-058 F）。
 *
 * <p>背景：CSV/Excel 单元格以 {@code = + - @} 或制表符开头时，Excel 打开会把内容
 * 当公式求值（DDE 注入经典载体，如 {@code =cmd|'/C calc'}）。导出值源于用户输入的
 * 自由文本（备注/商品名/货架号等），必须在写单元格前消毒：危险前缀前置单引号
 * （Excel 文本转义惯例，显示时不带引号）。数值/日期单元格走原生类型不经本类。
 */
public final class ExcelSanitizer {

    private ExcelSanitizer() {
    }

    /** 消毒文本单元格：危险前缀（=+-@ 与制表符）前置单引号；null/空串原样返回。 */
    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t') {
            return "'" + value;
        }
        return value;
    }
}
