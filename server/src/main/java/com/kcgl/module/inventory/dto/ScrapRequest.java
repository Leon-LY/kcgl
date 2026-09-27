package com.kcgl.module.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 报废请求：在库→已出库，任意销售态→取消。原因必填（docs/01 7.2 SCRAP reason 必填）。
 */
public record ScrapRequest(
        @NotNull Long itemId,
        @NotBlank String clientReqId,
        /** 报废原因（损坏/丢失等，落 stock_ledger.reason）。 */
        @NotBlank @Size(max = 255) String reason) {
}
