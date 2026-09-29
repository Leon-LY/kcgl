package com.kcgl.module.inventory;

import com.kcgl.common.web.ApiResponse;
import com.kcgl.module.auth.KcglUserDetails;
import com.kcgl.module.inventory.dto.ActionResult;
import com.kcgl.module.inventory.dto.ArrivalRequest;
import com.kcgl.module.inventory.dto.ArrivalResponse;
import com.kcgl.module.inventory.dto.MarkCanceledRequest;
import com.kcgl.module.inventory.dto.MarkListedRequest;
import com.kcgl.module.inventory.dto.PendingArrivalResponse;
import com.kcgl.module.inventory.dto.ReturnRequest;
import com.kcgl.module.inventory.dto.ScrapRequest;
import com.kcgl.module.inventory.dto.SellRequest;
import com.kcgl.module.inventory.dto.TransferRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存端点（M2-8a 到货核对；M3-⑤ 动作端点=边表展开：卖出/报废/调拨/退货双向/上架标记）。
 * 动作全部 E+（docs/01 六节权限矩阵）；统一幂等契约（clientReqId，docs/01 7.0）。
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final ArrivalService arrivalService;
    private final InventoryActionService actionService;

    public InventoryController(ArrivalService arrivalService, InventoryActionService actionService) {
        this.arrivalService = arrivalService;
        this.actionService = actionService;
    }

    /** 在途清单（到货核对页）：可选预计仓库筛选，卡片=缩略图+管理号+落札日+预计仓库。 */
    @GetMapping("/arrivals/pending")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public ApiResponse<PendingArrivalResponse> pendingArrivals(
            @RequestParam(required = false) Integer warehouse,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(arrivalService.pending(warehouse, page, size));
    }

    /** 批量确认入库（在途→在库，可改仓/上架货架/入库日）：同批全成全败，逐行幂等。 */
    @PostMapping("/arrivals")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<ArrivalResponse> arrive(@Valid @RequestBody ArrivalRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(arrivalService.arrive(req,
                operator.getUserId(), operator.getDisplayName()));
    }

    // ------------------------------------------------------------- 动作端点（M3-⑤）

    /** 卖出（在库→已出库，销售态→成交）：soldPrice 选填=线下直卖成交价（A10）。 */
    @PostMapping("/sell")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<ActionResult> sell(@Valid @RequestBody SellRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(actionService.sell(req,
                operator.getUserId(), operator.getDisplayName()));
    }

    /** 报废（在库→已出库，销售态→取消）：原因必填。 */
    @PostMapping("/scrap")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<ActionResult> scrap(@Valid @RequestBody ScrapRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(actionService.scrap(req,
                operator.getUserId(), operator.getDisplayName()));
    }

    /** 调拨（在库↔在库，仓 A→B）：ledger 双向入账，stock 不变。 */
    @PostMapping("/transfer")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<ActionResult> transfer(@Valid @RequestBody TransferRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(actionService.transfer(req,
                operator.getUserId(), operator.getDisplayName()));
    }

    /** 退货（direction：1顾客退回=已出库→在库 2退回拍卖场=在途/在库→已出库）。 */
    @PostMapping("/return")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<ActionResult> returnItem(@Valid @RequestBody ReturnRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(actionService.returnItem(req,
                operator.getUserId(), operator.getDisplayName()));
    }

    /** 手动上架标记（LIST_UP，在库且未上架/已取消→在售）：雅虎手工出品后即时登记。 */
    @PostMapping("/mark-listed")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<ActionResult> markListed(@Valid @RequestBody MarkListedRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(actionService.markListed(req,
                operator.getUserId(), operator.getDisplayName()));
    }

    /** 手动取消标记（CANCEL_MARK，在库且在售→取消，D-069）：流拍/出品取消的登记口。 */
    @PostMapping("/mark-canceled")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<ActionResult> markCanceled(@Valid @RequestBody MarkCanceledRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(actionService.markCanceled(req,
                operator.getUserId(), operator.getDisplayName()));
    }
}
