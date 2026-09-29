package com.kcgl.module.setting;

import java.util.Set;

/**
 * sys_setting 键目录（docs/01 5.3：运行时开关收敛后 admin UI 可调的仅此 5 项，
 * D-007；checklist.print_done 是标记类键、非 admin 设置页管理面，常量留在 ChecklistService）。
 * 键名是对外契约（前端 settings 页与既有行兼容），不得改名。
 */
public final class SettingKeys {

    public static final String SLOW_MOVE_WARN_DAYS = "slow_move.warn_days";
    public static final String SLOW_MOVE_ALARM_DAYS = "slow_move.alarm_days";
    public static final String LABEL_PRESET = "label.preset";
    public static final String LABEL_WIDTH = "label.width";
    public static final String LABEL_HEIGHT = "label.height";

    /** admin 设置页可写键全集（PUT /api/settings/{key} 白名单）。 */
    public static final Set<String> EDITABLE_KEYS = Set.of(
            SLOW_MOVE_WARN_DAYS, SLOW_MOVE_ALARM_DAYS,
            LABEL_PRESET, LABEL_WIDTH, LABEL_HEIGHT);

    private SettingKeys() {
    }
}
