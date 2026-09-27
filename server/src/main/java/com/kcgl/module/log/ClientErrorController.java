package com.kcgl.module.log;

import com.kcgl.common.web.ApiResponse;
import com.kcgl.common.web.PageResponse;
import com.kcgl.module.auth.KcglUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端错误上报端点（docs/01 六节）：POST 所有登录用户（未登录 401 挡匿名灌库）；
 * GET 仅管理员（URL 级 RBAC）。@Size 上限为防御性拒绝（恶意超大 body），
 * 限内由服务端截断到列宽——错误上报宁可截断不可拒收。
 */
@RestController
public class ClientErrorController {

    private final ClientErrorService service;

    public ClientErrorController(ClientErrorService service) {
        this.service = service;
    }

    public record ClientErrorRequest(
            @NotBlank @Size(max = 10000) String message,
            @Size(max = 100000) String stack,
            @Size(max = 512) String route,
            @Size(max = 8) String locale,
            @Size(max = 64) String appVersion,
            @Size(max = 16) String errorId,
            Integer queuePending,
            Integer queueOldestAgeSec) {
    }

    @PostMapping("/api/client-errors")
    public ApiResponse<Void> report(@AuthenticationPrincipal KcglUserDetails user,
            @Valid @RequestBody ClientErrorRequest req,
            @org.springframework.web.bind.annotation.RequestHeader(value = "User-Agent", required = false) String ua) {
        service.report(user.getUserId(), req.message(), req.stack(), req.route(), ua,
                req.locale(), req.appVersion(), req.errorId(), req.queuePending(), req.queueOldestAgeSec());
        return ApiResponse.ok();
    }

    @GetMapping("/api/client-errors")
    public ApiResponse<PageResponse<ClientErrorEntity>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(service.page(page, size));
    }
}
