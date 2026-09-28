package com.kcgl.module.excel;

import com.kcgl.common.web.ApiResponse;
import com.kcgl.module.auth.KcglUserDetails;
import com.kcgl.module.excel.dto.ExcelImportBatchResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * Excel 端点（docs/01 六节权限矩阵，D-058 K）：模板/导入=E+（导入写操作）；批次
 * 报告/导出=全员（V 可见成本/利润，A19 需求字面默认）。上传同步段毫秒级返回
 * batchId，终态由前端轮询/SSE 接力（TYPE_EXCEL_IMPORT）。
 *
 * <p>下载两端点用 StreamingResponseBody（模板/导出均为流式生成，不落盘不进堆）；
 * 导出的筛选校验在响应头提交前完成（见 ExcelExportService.requireValidFilters）。
 */
@RestController
@RequestMapping("/api/excel")
public class ExcelController {

    /** xlsx MIME（无 Media 常量，模板与导出共用）。 */
    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final String TEMPLATE_FILENAME = "商品登録テンプレート.xlsx";

    private final ExcelTemplateService templateService;
    private final ExcelImportService importService;
    private final ExcelExportService exportService;

    public ExcelController(ExcelTemplateService templateService, ExcelImportService importService,
            ExcelExportService exportService) {
        this.templateService = templateService;
        this.importService = importService;
        this.exportService = exportService;
    }

    /** 模板下载（双 Sheet：商品表头+記入方法）。 */
    @GetMapping("/items/template")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ResponseEntity<StreamingResponseBody> template() {
        StreamingResponseBody body = templateService::writeTemplate;
        return attachment(TEMPLATE_FILENAME, body);
    }

    /** Excel 上传（同步段）：sha 重复 409、队列满 429；返回 processing 批次。 */
    @PostMapping(value = "/items/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<ExcelImportBatchResponse> upload(@RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal KcglUserDetails operator) {
        // operatorName=username：与 AuditRecorder 会话路径（SecurityContext 记 username）同名，
        // 后台 6 参审计与 web 路径的操作人字段口径一致
        return ApiResponse.ok(importService.start(file, operator.getUserId(), operator.getUsername()));
    }

    /** 批次列表（最新 50）。 */
    @GetMapping("/items/imports")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public ApiResponse<List<ExcelImportBatchResponse>> list() {
        return ApiResponse.ok(importService.listRecent());
    }

    /** 批次详情（含错误行采样与跳变说明）。 */
    @GetMapping("/items/imports/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public ApiResponse<ExcelImportBatchResponse> detail(@PathVariable long id) {
        return ApiResponse.ok(importService.find(id));
    }

    /**
     * 流式导出（报告口径 25 列）。筛选同打印列表：必填创建日区间、可选会场/管理番号
     * （単票抽出）；码条件优先于日期区间。
     */
    @GetMapping("/items/export")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(required = false) Long venueId,
            @RequestParam(required = false) String code) {
        exportService.requireValidFilters(createdFrom, createdTo, code);
        StreamingResponseBody body = out ->
                exportService.writeExport(out, createdFrom, createdTo, venueId, code);
        return attachment(exportService.exportFilename(), body);
    }

    /** 附件响应（RFC 5987 日文文件名）。 */
    private static ResponseEntity<StreamingResponseBody> attachment(String filename,
            StreamingResponseBody body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8).build().toString())
                .contentType(XLSX)
                .body(body);
    }
}
