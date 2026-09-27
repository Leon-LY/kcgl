package com.kcgl.module.item.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 作废请求（POST /api/items/{id}/void）。reason 必填——扫旧码提示与审计都要展示。
 */
public record VoidItemRequest(
        /** 幂等键（同 CREATE 语义：重放返回原结果 200，不产生第二条流水）。 */
        @Size(max = 36) String clientReqId,
        @NotBlank @Size(max = 255) String reason) {
}
