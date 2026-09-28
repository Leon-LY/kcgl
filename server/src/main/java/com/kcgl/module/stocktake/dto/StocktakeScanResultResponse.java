package com.kcgl.module.stocktake.dto;

import com.kcgl.module.item.dto.ItemResponse;

/**
 * 盘点扫码响应：repeated=同单同件重复扫（服务端照记一次，200 不报错，docs/01 7.3）；
 * item 全量字段驱动卡内警示派生（他仓/冻结/非在库——照记不拦）。
 */
public record StocktakeScanResultResponse(
        boolean repeated,
        ItemResponse item,
        String thumbUrl) {
}
