package com.kcgl.module.checklist;

/**
 * 首启 checklist 状态（docs/01 4.3 首启卡片，六轮旅程 M2：空字典首件录入必 400 硬阻断，
 * 引导管理员按序完成五步）。会/档位按「启用中」判定——停用即视为未完成（录入前置）。
 */
public record ChecklistResponse(
        boolean hasStaffUser,
        boolean hasVenue,
        boolean hasPriceBand,
        boolean hasItem,
        boolean printDone) {
}
