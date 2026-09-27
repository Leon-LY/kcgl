package com.kcgl.module.inventory.dto;

/**
 * 动作端点统一结果：扫码确认卡与前端状态刷新的最小充分集（动作后的现态快照）。
 */
public record ActionResult(
        long itemId,
        String itemCode,
        int stockStatus,
        int saleStatus,
        int warehouse) {
}
