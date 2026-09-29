package com.kcgl.module.item.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 软删/恢复请求（DELETE /api/items/{id}、POST /{id}/restore，D-064）。
 * clientReqId=幂等键（stock_ledger CHAR(36) 列宽契约）；reason 可选——软删是数据治理动作
 * （区别于作废必填的业务追责），场景=测试件清理/重复件合并，随流水留痕不强制。
 */
public record RecycleActionRequest(
        @NotBlank @Size(max = 36) String clientReqId,
        @Size(max = 255) String reason) {
}
