package com.kcgl.module.yahoo.dto;

import com.kcgl.module.yahoo.YahooImportBatchEntity;
import com.kcgl.module.yahoo.YahooImportService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 受注导入批次报告（docs/01 7.4）：上传同步段即回（processing 态），前端轮询/SSE
 * 失效后重取终态。status：0处理中/1完成/2失败；errorRows 为采样前 1000 条；
 * note=まとめ売り等批次级補足。rowCount=物理数据行，matched/unmatched 按子行
 * （まとめ売り一行拆 N 子行）。
 *
 * <p>note/errorMessage 是日文兜底原文，noteJson/errorMessageCode+Params 是结构化
 * 提示（前端按当前语言渲染，见 D-127）；三者都发给前端，由其决定用哪个。
 */
public record ImportBatchResponse(
        Long id, String originalFilename, Integer status,
        Integer rowCount, Integer matchedCount, Integer unmatchedCount, Integer updatedCount,
        String note, String noteJson, String errorMessage, String errorMessageCode,
        String errorMessageParams, Long uploadedBy,
        LocalDateTime createdAt, LocalDateTime finishedAt,
        List<YahooImportService.ErrorRow> errorRows) {

    public static ImportBatchResponse of(YahooImportBatchEntity batch,
            List<YahooImportService.ErrorRow> errorRows) {
        return new ImportBatchResponse(batch.getId(), batch.getOriginalFilename(),
                batch.getStatus(), batch.getRowCount(),
                batch.getMatchedCount(), batch.getUnmatchedCount(), batch.getUpdatedCount(),
                batch.getNote(), batch.getNoteJson(), batch.getErrorMessage(),
                batch.getErrorMessageCode(), batch.getErrorMessageParams(), batch.getUploadedBy(),
                batch.getCreatedAt(), batch.getFinishedAt(), errorRows);
    }
}
