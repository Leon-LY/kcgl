package com.kcgl.module.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 退货请求（双向，direction 分流，docs/01 7.2）：
 * 1=顾客退回（已出库→在库，须成交态）2=退回拍卖场（在途/在库→已出库）。
 */
public record ReturnRequest(
        @NotNull Long itemId,
        @NotBlank String clientReqId,
        /** 退货方向：1顾客退回 2退回拍卖场。 */
        @NotNull Integer direction,
        /** 退货说明（选填，落 stock_ledger.reason）。 */
        @Size(max = 255) String note) {
}
