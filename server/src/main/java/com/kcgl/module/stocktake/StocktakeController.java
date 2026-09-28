package com.kcgl.module.stocktake;

import com.kcgl.common.web.ApiResponse;
import com.kcgl.module.auth.KcglUserDetails;
import com.kcgl.module.stocktake.dto.StocktakeCreateRequest;
import com.kcgl.module.stocktake.dto.StocktakeDiffActionRequest;
import com.kcgl.module.stocktake.dto.StocktakeDiffActionResponse;
import com.kcgl.module.stocktake.dto.StocktakeDiffListResponse;
import com.kcgl.module.stocktake.dto.StocktakeListResponse;
import com.kcgl.module.stocktake.dto.StocktakeScanRequest;
import com.kcgl.module.stocktake.dto.StocktakeScanResultResponse;
import com.kcgl.module.stocktake.dto.StocktakeSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 盘点端点（M3-⑥，docs/01 六节权限矩阵）：读全员，写 E+；cancel 服务端强校验
 * 发起人身份；差异处理 CONFIRM/IGNORE 统一幂等契约（clientReqId，docs/01 7.0）。
 */
@RestController
@RequestMapping("/api/stocktakes")
public class StocktakeController {

    private final StocktakeService stocktakeService;

    public StocktakeController(StocktakeService stocktakeService) {
        this.stocktakeService = stocktakeService;
    }

    /** 发起盘点（选仓）：同仓已有进行中单 → 409009（前端引导直达既有单）。 */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<StocktakeSummaryResponse> create(@Valid @RequestBody StocktakeCreateRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(stocktakeService.create(req, operator.getUserId()));
    }

    /** 盘点单列表（创建时间倒序分页）；status 省略=全部。 */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public ApiResponse<StocktakeListResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer status,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(stocktakeService.list(page, size, status, operator.getUserId()));
    }

    /** 单条详情（扫码页/差异页头部计数）。 */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public ApiResponse<StocktakeSummaryResponse> detail(@PathVariable long id,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(stocktakeService.detail(id, operator.getUserId()));
    }

    /** 盘点扫码：照记不拦（他仓/冻结/非在库卡内警示由前端派生）；重复扫 repeated=true。 */
    @PostMapping("/{id}/scans")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<StocktakeScanResultResponse> scan(@PathVariable long id,
            @Valid @RequestBody StocktakeScanRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(stocktakeService.scan(id, req.code(), operator.getUserId()));
    }

    /** close：冻结期望集合（该仓在库未删未废）→ 生成差异表 → 转待确认。 */
    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<StocktakeSummaryResponse> close(@PathVariable long id,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(stocktakeService.close(id, operator.getUserId()));
    }

    /** 发起人撤自己的单（仅进行中可撤；非发起人 403001）。 */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<StocktakeSummaryResponse> cancel(@PathVariable long id,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(stocktakeService.cancel(id, operator.getUserId()));
    }

    /** 差异表（待确认优先分页）；confirmStatus 省略=全部。 */
    @GetMapping("/{id}/diffs")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public ApiResponse<StocktakeDiffListResponse> diffs(@PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer confirmStatus) {
        return ApiResponse.ok(stocktakeService.diffs(id, page, size, confirmStatus));
    }

    /** 差异处理：CONFIRM=应用变更+流水（clientReqId 幂等）；IGNORE=仅置位（状态幂等）。 */
    @PostMapping("/{id}/diffs/{diffId}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<StocktakeDiffActionResponse> resolve(@PathVariable long id,
            @PathVariable long diffId,
            @Valid @RequestBody StocktakeDiffActionRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(stocktakeService.resolve(id, diffId, req,
                operator.getUserId(), operator.getDisplayName()));
    }
}
