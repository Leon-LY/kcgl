package com.kcgl.module.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 调拨请求：在库↔在库，仓 A→B（stock 不变，wh 在 ledger 双向入账表达）。
 * 作业约定：调拨在到货卸货端执行（docs/01 7.2），防在途期间系统仓与实物仓错位撞盘点。
 */
public record TransferRequest(
        @NotNull Long itemId,
        @NotBlank String clientReqId,
        /** 目标仓库：1名古屋 2福岡。 */
        @NotNull Integer toWarehouse) {
}
