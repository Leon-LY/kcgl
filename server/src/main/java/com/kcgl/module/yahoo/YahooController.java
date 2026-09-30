package com.kcgl.module.yahoo;

import com.kcgl.common.web.ApiResponse;
import com.kcgl.module.auth.KcglUserDetails;
import com.kcgl.module.yahoo.dto.ImportBatchResponse;
import com.kcgl.module.yahoo.dto.PendingShipmentResponse;
import com.kcgl.module.yahoo.dto.ReconcileResponse;
import com.kcgl.module.yahoo.dto.UnmatchedRowsResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 雅虎端点（docs/01 六节权限矩阵）：导入写=E+（异步批次，报告读全员）；
 * 对账/出荷待ち读=全员。CSV 上传同步段毫秒级返回 batchId，终态由前端
 * 轮询/SSE 接力（TYPE_YAHOO_IMPORT）。
 */
@RestController
@RequestMapping("/api/yahoo")
public class YahooController {

    private final YahooImportService importService;
    private final YahooReconcileService reconcileService;

    public YahooController(YahooImportService importService, YahooReconcileService reconcileService) {
        this.importService = importService;
        this.reconcileService = reconcileService;
    }

    /** CSV 上传（同步段）：sha 重复 409、队列满 429；返回 processing 批次。 */
    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<ImportBatchResponse> upload(@RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(importService.start(file, operator.getUserId(),
                operator.getUsername(), operator.getDisplayName()));
    }

    /** 批次列表（最新 50）。 */
    @GetMapping("/imports")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public ApiResponse<List<ImportBatchResponse>> list() {
        return ApiResponse.ok(importService.listRecent());
    }

    /** 批次详情（含错误行采样）。 */
    @GetMapping("/imports/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public ApiResponse<ImportBatchResponse> detail(@PathVariable long id) {
        return ApiResponse.ok(importService.find(id));
    }

    /** 批次不一致行明细（「受注有而系统无」：原文自码逐行可查，D-105）。 */
    @GetMapping("/imports/{id}/unmatched")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public ApiResponse<UnmatchedRowsResponse> unmatched(@PathVariable long id) {
        return ApiResponse.ok(reconcileService.unmatchedOf(id));
    }

    /** 对账三活视图。 */
    @GetMapping("/reconcile")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public ApiResponse<ReconcileResponse> reconcile() {
        return ApiResponse.ok(reconcileService.reconcile());
    }

    /** 出荷待ち清单（已成交未出库，货架号序+缩略图）。 */
    @GetMapping("/pending-shipments")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public ApiResponse<PendingShipmentResponse> pendingShipments() {
        return ApiResponse.ok(reconcileService.pendingShipments());
    }
}
