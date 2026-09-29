package com.kcgl.module.setting;

import com.kcgl.common.audit.AuditRecorder;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 系统设置读写（docs/01 5.3：运行时开关收敛后仅滞销阈值/标签尺寸/完成标记类）。
 * 不做内存缓存——消费方（checklist/搜索/打印页加载）均为页级低频读取，
 * 缓存+SSE 失效广播是 YAGNI；出现高频读者再加。
 *
 * <p>M5-③：admin 设置页落库——快照 {@link AppSettings}（GET）与带校验的
 * {@link #updateSetting}（PUT，A-only 由控制器 @PreAuthorize 把守）。脏值防御：
 * 既有行值非法时读侧回退默认（30/90/small），写侧校验拒绝——写入路径全部经过
 * 本类后不可能再出现脏值。
 */
@Service
public class SettingService {

    /** 滞销阈值（天）上下限：1 天～10 年。 */
    private static final int DAYS_MIN = 1;
    private static final int DAYS_MAX = 3650;
    /** 自定义标签尺寸上下限（mm）：下限=最小预置 38×21 的可印底线（QR≥13mm+人读码位），上限=A4 半幅内 sane 值。 */
    private static final int LABEL_WIDTH_MIN = 30;
    private static final int LABEL_WIDTH_MAX = 100;
    private static final int LABEL_HEIGHT_MIN = 21;
    private static final int LABEL_HEIGHT_MAX = 60;
    private static final Set<String> LABEL_PRESET_VALUES = Set.of("small", "medium", "large", "custom");

    private final SysSettingMapper mapper;
    private final AuditRecorder auditRecorder;

    public SettingService(SysSettingMapper mapper, AuditRecorder auditRecorder) {
        this.mapper = mapper;
        this.auditRecorder = auditRecorder;
    }

    public Optional<String> findValue(String key) {
        return Optional.ofNullable(mapper.selectById(key)).map(SysSettingEntity::getValue);
    }

    /** 幂等 upsert：值未变不写（保 updated_at 语义——「最后一次真正变更」。 */
    public void putValue(String key, String value, Long updatedBy) {
        SysSettingEntity existing = mapper.selectById(key);
        if (existing == null) {
            SysSettingEntity entity = new SysSettingEntity();
            entity.setKey(key);
            entity.setValue(value);
            entity.setUpdatedBy(updatedBy);
            mapper.insert(entity);
        } else if (!Objects.equals(existing.getValue(), value)) {
            existing.setValue(value);
            existing.setUpdatedBy(updatedBy);
            mapper.updateById(existing);
        }
    }

    /** 滞销黄/红阈值（D-065 唯一定义的读侧出口：搜索筛选/行徽标/统计聚合共用）。 */
    public SlowMoveThresholds slowMoveThresholds() {
        return new SlowMoveThresholds(
                daysValue(SettingKeys.SLOW_MOVE_WARN_DAYS, AppSettings.DEFAULT_WARN_DAYS),
                daysValue(SettingKeys.SLOW_MOVE_ALARM_DAYS, AppSettings.DEFAULT_ALARM_DAYS));
    }

    /** 设置页快照（GET /api/settings）。 */
    public AppSettings appSettings() {
        return new AppSettings(
                daysValue(SettingKeys.SLOW_MOVE_WARN_DAYS, AppSettings.DEFAULT_WARN_DAYS),
                daysValue(SettingKeys.SLOW_MOVE_ALARM_DAYS, AppSettings.DEFAULT_ALARM_DAYS),
                presetValue(),
                intValue(SettingKeys.LABEL_WIDTH, AppSettings.DEFAULT_LABEL_WIDTH_MM),
                intValue(SettingKeys.LABEL_HEIGHT, AppSettings.DEFAULT_LABEL_HEIGHT_MM));
    }

    /**
     * 更新一个设置键（PUT /api/settings/{key}，管理员）。校验通过才落库；
     * 值未变=无操作放行（幂等 PUT）。跨字段规则：alarm 必须大于 warn
     * （红阈值低于黄阈值会让黄区间为空）——改任一侧都对照另一侧现值校验。
     * 审计与写同事务（D-026：审计写失败则业务一并回滚）。
     */
    @Transactional
    public AppSettings updateSetting(String key, String value, Long operatorId) {
        if (!SettingKeys.EDITABLE_KEYS.contains(key)) {
            throw new BizException(ErrorCode.NOT_FOUND, "存在しない設定キーです: " + key);
        }
        String newValue = value == null ? "" : value.trim();
        switch (key) {
            case SettingKeys.SLOW_MOVE_WARN_DAYS -> {
                int days = requireDays(newValue);
                int alarm = daysValue(SettingKeys.SLOW_MOVE_ALARM_DAYS, AppSettings.DEFAULT_ALARM_DAYS);
                if (days >= alarm) {
                    throw new BizException(ErrorCode.SETTING_VALUE_INVALID,
                            "黄色しきい値は赤色しきい値（" + alarm + "日）より小さくしてください");
                }
            }
            case SettingKeys.SLOW_MOVE_ALARM_DAYS -> {
                int days = requireDays(newValue);
                int warn = daysValue(SettingKeys.SLOW_MOVE_WARN_DAYS, AppSettings.DEFAULT_WARN_DAYS);
                if (days <= warn) {
                    throw new BizException(ErrorCode.SETTING_VALUE_INVALID,
                            "赤色しきい値は黄色しきい値（" + warn + "日）より大きくしてください");
                }
            }
            case SettingKeys.LABEL_PRESET -> {
                if (!LABEL_PRESET_VALUES.contains(newValue)) {
                    throw new BizException(ErrorCode.SETTING_VALUE_INVALID,
                            "ラベルの規格は small / medium / large / custom のいずれかです");
                }
            }
            case SettingKeys.LABEL_WIDTH -> requireRange(newValue, LABEL_WIDTH_MIN, LABEL_WIDTH_MAX, "ラベル幅");
            case SettingKeys.LABEL_HEIGHT -> requireRange(newValue, LABEL_HEIGHT_MIN, LABEL_HEIGHT_MAX, "ラベル高さ");
            default -> throw new BizException(ErrorCode.NOT_FOUND, "存在しない設定キーです: " + key);
        }

        String oldValue = findValue(key).orElse(null);
        if (!Objects.equals(oldValue, newValue)) {
            putValue(key, newValue, operatorId);
            auditRecorder.record("SETTING_UPDATE", "sys_setting", null,
                    Map.of("key", key, "oldValue", oldValue == null ? "" : oldValue, "newValue", newValue));
        }
        return appSettings();
    }

    // ------------------------------------------------------------- 读侧解析

    private int daysValue(String key, int fallback) {
        return findValue(key).flatMap(this::parsePositiveDays).orElse(fallback);
    }

    private int intValue(String key, int fallback) {
        return findValue(key)
                .map(String::trim)
                .flatMap(v -> {
                    try {
                        return Optional.of(Integer.parseInt(v));
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                })
                .orElse(fallback);
    }

    private String presetValue() {
        return findValue(SettingKeys.LABEL_PRESET)
                .map(String::trim)
                .filter(LABEL_PRESET_VALUES::contains)
                .orElse(AppSettings.DEFAULT_LABEL_PRESET);
    }

    /** 脏值防御回退：非正整数视为未设置。 */
    private Optional<Integer> parsePositiveDays(String value) {
        try {
            int days = Integer.parseInt(value.trim());
            return days > 0 ? Optional.of(days) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------- 写侧校验

    private int requireDays(String value) {
        int days = requireInt(value, "しきい値");
        if (days < DAYS_MIN || days > DAYS_MAX) {
            throw new BizException(ErrorCode.SETTING_VALUE_INVALID,
                    "しきい値は " + DAYS_MIN + "〜" + DAYS_MAX + " 日の範囲で指定してください");
        }
        return days;
    }

    private void requireRange(String value, int min, int max, String label) {
        int parsed = requireInt(value, label);
        if (parsed < min || parsed > max) {
            throw new BizException(ErrorCode.SETTING_VALUE_INVALID,
                    label + "は " + min + "〜" + max + " mm の範囲で指定してください");
        }
    }

    private int requireInt(String value, String label) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.SETTING_VALUE_INVALID, label + "は数値で指定してください");
        }
    }

    /** 滞销黄/红阈值对（搜索/统计共用，D-065）。 */
    public record SlowMoveThresholds(int warnDays, int alarmDays) {
    }
}
