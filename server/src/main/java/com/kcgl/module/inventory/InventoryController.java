package com.kcgl.module.inventory;

import com.kcgl.common.web.ApiResponse;
import com.kcgl.module.auth.KcglUserDetails;
import com.kcgl.module.inventory.dto.ArrivalRequest;
import com.kcgl.module.inventory.dto.ArrivalResponse;
import com.kcgl.module.inventory.dto.PendingArrivalResponse;
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
 * 库存端点（M2-8a 到货核对切片；五类出库/调拨/退货/上架标记随 M3 按边表逐个展开）。
 * 到货核对页：GET 在途清单全员可看，POST 确认入库=E+。
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final ArrivalService arrivalService;

    public InventoryController(ArrivalService arrivalService) {
        this.arrivalService = arrivalService;
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
}
