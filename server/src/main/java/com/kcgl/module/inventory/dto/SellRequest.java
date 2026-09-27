package com.kcgl.module.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 卖出请求（扫码/详情页）：在库→已出库，销售态→成交（A10：未上架线下直卖允许）。
 * soldPrice 选填——雅虎 CSV 成交价优先（docs/01 7.2 sold_price 双源），此处手填仅覆盖空值场景。
 */
public record SellRequest(
        @NotNull Long itemId,
        @NotBlank String clientReqId,
        /** 手填成交价（1-99,999,999 円；null=未填，待 CSV 回填）。 */
        Long soldPrice) {
}
