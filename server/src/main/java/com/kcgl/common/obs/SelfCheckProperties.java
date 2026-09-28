package com.kcgl.common.obs;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 每日自检配置（M3-⑦，docs/01 5.3/9.3）：数据量阈值与磁盘告警水位默认按「活库存」
 * 口径（A20 确认单项——甲方答「历史累计」后调阈值并启动归档预案即可）；scheduled
 * 开关默认开，测试置 false 关闭定时触发。
 */
@ConfigurationProperties(prefix = "kcgl.self-check")
public record SelfCheckProperties(
        Long itemVolumeThreshold,
        Long ledgerVolumeThreshold,
        Long logVolumeThreshold,
        Integer diskWarnPercent,
        Boolean scheduled) {

    public SelfCheckProperties {
        if (itemVolumeThreshold == null) {
            itemVolumeThreshold = 150_000L;
        }
        if (ledgerVolumeThreshold == null) {
            ledgerVolumeThreshold = 3_000_000L;
        }
        if (logVolumeThreshold == null) {
            logVolumeThreshold = 3_000_000L;
        }
        if (diskWarnPercent == null) {
            diskWarnPercent = 80;
        }
        if (scheduled == null) {
            scheduled = true;
        }
    }
}
