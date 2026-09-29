package com.kcgl.module.itemcode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 管理号格式与进制逻辑（docs/01 7.1 唯一定义节；D-068 去年代号）。
 * 格式：{会场2}{月1-2不补零}-{流水前缀}{1-99}{价格码1?}
 * 例：HT9-A1X = 会场 HT + 9 月 + 前缀 A + 流水 1 + 价格档 X。
 * 会场码恒 2 字母定长，去年代号后无前缀碰撞歧义；桶=(会场,月)跨年连续。
 * 前缀 base-26 递进：A→Z→AA→AAA（覆盖单桶 180 万+ 号，远超业务上限）。
 * includePriceCode=false 时省略末位价格码（A1 口径，application.yml 配置）。
 */
public final class ItemCodeFormatter {

    /** 拆装用完整正则（锚定）：五段分组，价格码可选。 */
    static final Pattern CODE_PATTERN = Pattern.compile(
            "^([A-Z]{2})(1[0-2]|[1-9])-([A-Z]{1,3})([1-9][0-9]?)([A-Z])?$");

    /** 管理号形态校验（CSV 清洗/搜索快路径共用）：完整正则锚定匹配。 */
    public static boolean matches(String code) {
        return CODE_PATTERN.matcher(code).matches();
    }

    private ItemCodeFormatter() {
    }

    /** 组装管理号。month 不补零（1-9 单字符、10-12 双字符）；seq 不补零（1-99）。 */
    public static String format(String venueCode, int month,
            String prefix, int seq, String bandCode, boolean includePriceCode) {
        String tail = includePriceCode ? prefix + seq + bandCode : prefix + seq;
        return venueCode + month + "-" + tail;
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

    /**
     * 前缀位置序权值（base-26 大端：A=1、Z=26、AA=27、AZ=52、AAA=703）——长度优先再
     * 字典序，与 {@link #nextPrefix} 进位序一致。不可用 String.compareTo 替代：字典序
     * "Z"＞"AA" 与进位序 Z＜AA 方向相反（Excel 旧号导入判定计数器是否被超越，D-058 D）。
     */
    public static int prefixRank(String prefix) {
        int rank = 0;
        for (int i = 0; i < prefix.length(); i++) {
            rank = rank * 26 + (prefix.charAt(i) - 'A' + 1);
        }
        return rank;
    }

    /** 拆解管理号五段（与 format 互逆；无价格码时 bandCode=null）。 */
    public static ParsedCode parse(String code) {
        Matcher matcher = CODE_PATTERN.matcher(code);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("管理号の形式が不正です: " + code);
        }
        return new ParsedCode(
                matcher.group(1),
                Integer.parseInt(matcher.group(2)),
                matcher.group(3),
                Integer.parseInt(matcher.group(4)),
                matcher.group(5));
    }

    /** 拆解结果（bandCode 仅 includePriceCode=true 的号非空）。 */
    public record ParsedCode(String venueCode, int month,
            String prefix, int seq, String bandCode) {
    }
}
