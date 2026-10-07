package com.kcgl.module.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 手工修正请求（POST /api/items/{id}/adjust，D4，A-only）：管理员任意态覆盖
 * （docs/01 7.2 矩阵「手工修正（A，须 reason）」，库存/销售两轴 任意→任意）。
 *
 * <p>两轴均可选，缺省=保持该轴不变；但至少要给一轴，且给的值须落在状态域内
 * （由服务层校验，故此处不重复声明 @Min/@Max）。reason 必填——与作废同理，
 * 越权覆盖必须留下「为什么改」才能事后追责；软删那样可选 reason 的口径不适用。
 */
public record AdjustRequest(
        /** 幂等键（docs/01 7.0，stock_ledger CHAR(36) 列宽契约）。 */
        @NotBlank @Size(max = 36) String clientReqId,
        @NotBlank @Size(max = 255) String reason,
        /** 库存状态 0在途/1在库/2已出库；null=不变。 */
        Integer stockStatus,
        /** 销售状态 0未上架/1在售/2成交/3取消；null=不变。 */
        Integer saleStatus) {
}
