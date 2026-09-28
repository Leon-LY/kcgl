package com.kcgl.module.yahoo.dto;

import com.kcgl.module.yahoo.YahooImportBatchEntity;
import com.kcgl.module.yahoo.YahooImportService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 导入批次报告（docs/01 7.4）：上传同步段即回（processing 态），前端轮询/SSE
 * 失效后重取终态。status：0处理中/1完成/2失败；errorRows 为采样前 1000 条。
 */
public record ImportBatchResponse(
        Long id, String originalFilename, Integer status, String encodingDetected,
        Integer rowCount, Integer matchedCount, Integer unmatchedCount, Integer updatedCount,
        String errorMessage, Long uploadedBy, LocalDateTime createdAt, LocalDateTime finishedAt,
        List<YahooImportService.ErrorRow> errorRows) {

    public static ImportBatchResponse of(YahooImportBatchEntity batch,
            List<YahooImportService.ErrorRow> errorRows) {
        return new ImportBatchResponse(batch.getId(), batch.getOriginalFilename(),
                batch.getStatus(), batch.getEncodingDetected(), batch.getRowCount(),
                batch.getMatchedCount(), batch.getUnmatchedCount(), batch.getUpdatedCount(),
                batch.getErrorMessage(), batch.getUploadedBy(), batch.getCreatedAt(),
                batch.getFinishedAt(), errorRows);
    }
}
