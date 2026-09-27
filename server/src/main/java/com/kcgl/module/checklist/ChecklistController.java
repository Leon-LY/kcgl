package com.kcgl.module.checklist;

import com.kcgl.common.web.ApiResponse;
import com.kcgl.module.auth.KcglUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首启 checklist 端点（docs/01 4.3）：仅管理员——首启引导是管理员的初始化职责，
 * 非管理员无需感知系统就绪度（避免暴露账号/字典规模类内部状态）。
 */
@RestController
@RequestMapping("/api/checklist")
public class ChecklistController {

    private final ChecklistService checklistService;

    public ChecklistController(ChecklistService checklistService) {
        this.checklistService = checklistService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ChecklistResponse> get() {
        return ApiResponse.ok(checklistService.get());
    }

    @PostMapping("/print-done")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ChecklistResponse> markPrintDone(@AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(checklistService.markPrintDone(operator.getUserId()));
    }
}
