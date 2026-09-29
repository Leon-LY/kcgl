package com.kcgl.module.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 手动取消标记请求（CANCEL_MARK，D-069）：在库且在售→取消。
 * 受注表无取消信息（流拍/出品取消不在订单导出里），手动标记是 CANCEL_MARK
 * 唯一来源——流拍件由此进入照合「落札なし・再出品待ち」视图。
 */
public record MarkCanceledRequest(
        @NotNull Long itemId,
        @NotBlank String clientReqId) {
}
