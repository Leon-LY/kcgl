package com.kcgl.module.yahoo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 雅虎受注 xlsx 管线配置（docs/01 7.4，D-069）。列映射/表头契约钉死在
 * {@link YahooOrderParser}（受注导出 A-U 官方 21 列布局）而非配置——样张已到位，
 * 格式变更本需发版重验语义，配置面只留容量与阈值。
 */
@ConfigurationProperties(prefix = "kcgl.yahoo")
public record YahooProperties(
        String importsDir, Long maxFileBytes, Integer maxRows, Integer zombieMinutes,
        Integer shipmentDelayWarnDays) {

    public YahooProperties {
        if (importsDir == null || importsDir.isBlank()) {
            importsDir = "./data/imports";
        }
        if (maxFileBytes == null) {
            // 5MB 防御上限（受注周 44 行、年 ~2300 行，实际文件 KB 级；xlsx=zip 容器
            // ~100:1 放大，50MB 可流过 ~5GB XML——安全评审自 50MB 收紧）
            maxFileBytes = 5_242_880L;
        }
        if (maxRows == null) {
            maxRows = 100_000; // 防御上限（D-069 6：受注年 ~2300 行，整体下调）
        }
        if (zombieMinutes == null) {
            zombieMinutes = 30; // 僵尸批次自愈阈值：processing 超此时长标记失败
        }
        if (shipmentDelayWarnDays == null) {
            shipmentDelayWarnDays = 7; // 出荷待ち滞留标红阈值（对账视角）
        }
    }

    /**
     * 测试/默认实例工厂（全走 compact 构造器的兜底值）。
     * 必须是静态方法而非额外无参构造器：多一个非 private 构造器会让 Boot 放弃
     * 构造器绑定→JavaBean 绑定→record 无 setter→整个属性类静默不绑定
     * （与 ExcelProperties 同源缺陷，2026-09-28 全链路定位修复）。
     */
    public static YahooProperties defaults() {
        return new YahooProperties(null, null, null, null, null);
    }

    public Path importsRoot() {
        return Path.of(importsDir);
    }
}
