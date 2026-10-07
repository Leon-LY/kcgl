package com.kcgl.module.excel.dto;

import com.kcgl.module.excel.ExcelImportBatchEntity;
import com.kcgl.module.excel.ExcelImportService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Excel 导入批次报告（D-058 J）：上传同步段即回（processing 态），前端轮询/SSE
 * 失效后重取终态。status：0处理中/1完成/2失败；generated/imported=双模式计数
 * （生成/旧号导入）；note=计数器跳变说明聚合；errorRows 为采样前 1000 条。
 *
 * <p>errorMessage 是日文兜底原文，errorMessageCode+Params 是结构化提示
 * （前端按当前语言渲染，见 D-127）；两者都发给前端，由其决定用哪个。
 */
public record ExcelImportBatchResponse(
        Long id, String originalFilename, Integer status, Integer rowCount,
        Integer generatedCount, Integer importedCount, Integer errorCount, String note,
        String errorMessage, String errorMessageCode, String errorMessageParams, Long uploadedBy,
        LocalDateTime createdAt, LocalDateTime finishedAt,
        List<ExcelImportService.ErrorRow> errorRows) {

    public static ExcelImportBatchResponse of(ExcelImportBatchEntity batch,
            List<ExcelImportService.ErrorRow> errorRows) {
        return new ExcelImportBatchResponse(batch.getId(), batch.getOriginalFilename(),
                batch.getStatus(), batch.getRowCount(), batch.getGeneratedCount(),
                batch.getImportedCount(), batch.getErrorCount(), batch.getNote(),
                batch.getErrorMessage(), batch.getErrorMessageCode(),
                batch.getErrorMessageParams(), batch.getUploadedBy(), batch.getCreatedAt(),
                batch.getFinishedAt(), errorRows);
    }
}
