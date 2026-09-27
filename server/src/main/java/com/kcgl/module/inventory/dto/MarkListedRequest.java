package com.kcgl.module.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 手动上架标记请求（LIST_UP）：在库且未上架→在售。
 * 供雅虎手工出品后即时登记——消除出品→CSV 回传窗口的滞销误报（docs/01 7.2）。
 */
public record MarkListedRequest(
        @NotNull Long itemId,
        @NotBlank String clientReqId) {
}
