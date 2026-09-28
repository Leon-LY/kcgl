package com.kcgl.module.stocktake.dto;

import com.kcgl.module.stocktake.StocktakeEntity;

import java.time.LocalDateTime;

/**
 * 盘点单摘要（发起/列表/详情/close/cancel 共用，前端 StocktakeSummary 契约）。
 * pendingDiffCount 无表列——由 COUNT 派生（仅 close 后非空）；mine=发起人标记
 * （撤销按钮渲染依据，服务端仍强校验发起人身份）。
 */
public record StocktakeSummaryResponse(
        long id,
        String stocktakeNo,
        int warehouse,
        int status,
        Integer expectedCount,
        int scannedCount,
        Integer diffCount,
        Integer pendingDiffCount,
        LocalDateTime createdAt,
        LocalDateTime closedAt,
        String createdByName,
        String closedByName,
        boolean mine) {

    public static StocktakeSummaryResponse of(StocktakeEntity st, Integer pendingDiffCount,
            String createdByName, String closedByName, long currentUserId) {
        return new StocktakeSummaryResponse(
                st.getId(), st.getStocktakeNo(), st.getWarehouse(), st.getStatus(),
                st.getExpectedCount(), st.getScannedCount() == null ? 0 : st.getScannedCount(),
                st.getDiffCount(), pendingDiffCount,
                st.getCreatedAt(), st.getClosedAt(),
                createdByName, closedByName,
                st.getCreatedBy() != null && st.getCreatedBy() == currentUserId);
    }
}
