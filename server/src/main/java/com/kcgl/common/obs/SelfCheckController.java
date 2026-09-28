package com.kcgl.common.obs;

import com.kcgl.common.web.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手动「帳実自検」（docs/01 5.3，角色 A）：管理员即时触发全量检查，报告直出管理页；
 * 与每日 job 共用 checkAndAlert（发现即落 sys_alert 留痕，供其他管理员红点可见）。
 */
@RestController
public class SelfCheckController {

    private final SelfCheckService selfCheckService;

    public SelfCheckController(SelfCheckService selfCheckService) {
        this.selfCheckService = selfCheckService;
    }

    @PostMapping("/api/self-check")
    @PreAuthorize("hasAnyRole('ADMIN')")
    public ApiResponse<SelfCheckService.SelfCheckReport> run() {
        return ApiResponse.ok(selfCheckService.checkAndAlert());
    }
}
