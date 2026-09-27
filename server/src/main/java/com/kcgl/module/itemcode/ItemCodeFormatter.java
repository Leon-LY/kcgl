package com.kcgl.module.itemcode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 管理号格式与进制逻辑（docs/01 7.1 唯一定义节）。
 * 格式：{会场2}{年代号1}{月1-2不补零}-{流水前缀}{1-99}{价格码1?}
 * 例：HTK9-A1X = 会场 HT + 年代号 K(2026) + 9 月 + 前缀 A + 流水 1 + 价格档 X。
 * 前缀 base-26 递进：A→Z→AA→AAA（覆盖单桶 180 万+ 号，远超业务上限）。
 * includePriceCode=false 时省略末位价格码（A1 口径，application.yml 配置）。
 */
public final class ItemCodeFormatter {

    /** 拆装用完整正则（锚定）：六段分组，价格码可选。 */
    static final Pattern CODE_PATTERN = Pattern.compile(
            "^([A-Z]{2})([A-Z])(1[0-2]|[1-9])-([A-Z]{1,3})([1-9][0-9]?)([A-Z])?$");

    private ItemCodeFormatter() {
    }

    /** 组装管理号。month 不补零（1-9 单字符、10-12 双字符）；seq 不补零（1-99）。 */
    public static String format(String venueCode, String yearCode, int month,
            String prefix, int seq, String bandCode, boolean includePriceCode) {
        String tail = includePriceCode ? prefix + seq + bandCode : prefix + seq;
        return venueCode + yearCode + month + "-" + tail;
    }

    /** 前缀 base-26 进位：A→B、Z→AA、AZ→BA、ZZ→AAA（大端 26 进制 +1）。 */
    public static String nextPrefix(String prefix) {
        char[] chars = prefix.toCharArray();
        for (int i = chars.length - 1; i >= 0; i--) {
            if (chars[i] < 'Z') {
                chars[i]++;
                return new String(chars);
            }
            chars[i] = 'A';
        }
        return "A" + new String(chars);
    }

    /** 拆解管理号六段（与 format 互逆；无价格码时 bandCode=null）。 */
    public static ParsedCode parse(String code) {
        Matcher matcher = CODE_PATTERN.matcher(code);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("管理号の形式が不正です: " + code);
        }
        return new ParsedCode(
                matcher.group(1),
                matcher.group(2),
                Integer.parseInt(matcher.group(3)),
                matcher.group(4),
                Integer.parseInt(matcher.group(5)),
                matcher.group(6));
    }

    /** 拆解结果（bandCode 仅 includePriceCode=true 的号非空）。 */
    public record ParsedCode(String venueCode, String yearCode, int month,
            String prefix, int seq, String bandCode) {
    }
}
