package com.kcgl.module.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * 批量确认入库请求（到货核对页）：同一事务全成全败（docs/01 7.2）。
 * 每行独立 clientReqId（幂等键落 stock_ledger，UNIQUE 一键一行）——网络重放按行读回原结果。
 */
public record ArrivalRequest(@NotEmpty List<@Valid ArrivalLine> items, LocalDate warehouseInDate) {

    public record ArrivalLine(
            @NotNull Long itemId,
            @NotBlank String clientReqId,
            /** 到仓改仓（A7）：缺省沿用录入时的预计仓库。 */
            Integer warehouse,
            /** 到仓上架货架：缺省不改。 */
            String shelfNo) {
    }
}
