package com.kcgl.module.setting;

/**
 * admin 设置页快照（GET /api/settings 响应与 PUT 后返回值同构，docs/01 六节）。
 * 未落库键取默认值（warn 30 / alarm 90，A9；标签预置 small=38×21）。
 * labelWidthMm/labelHeightMm 仅在 labelPreset=custom 时被打印页消费。
 */
public record AppSettings(
        int warnDays,
        int alarmDays,
        String labelPreset,
        int labelWidthMm,
        int labelHeightMm) {

    public static final int DEFAULT_WARN_DAYS = 30;
    public static final int DEFAULT_ALARM_DAYS = 90;
    public static final String DEFAULT_LABEL_PRESET = "small";
    public static final int DEFAULT_LABEL_WIDTH_MM = 50;
    public static final int DEFAULT_LABEL_HEIGHT_MM = 30;
}
