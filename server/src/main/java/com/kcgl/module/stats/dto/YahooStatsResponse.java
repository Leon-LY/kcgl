package com.kcgl.module.stats.dto;

import java.time.LocalDateTime;

/**
 * 大盘雅虎同步指标：三活视图口径的计数（D-069——受注导入=成交事实集，
 * 在售/取消唯一来源是手动标记）+ 最近一次成功导入的新鲜度信息（无成功批次为 null）。
 */
public record YahooStatsResponse(
        long soldNotShipped,
        long canceledNotRelisted,
        long withdrawNeeded,
        LocalDateTime lastImportFinishedAt,
        String lastImportFilename) {
}
