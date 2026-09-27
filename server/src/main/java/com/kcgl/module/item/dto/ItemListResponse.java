package com.kcgl.module.item.dto;

import java.util.List;

/** 分页列表信封（M2-7 打印页首版；M5 全局搜索复用并扩展筛选字段）。 */
public record ItemListResponse(
        long total,
        int page,
        int size,
        List<ItemSummaryResponse> rows) {
}
