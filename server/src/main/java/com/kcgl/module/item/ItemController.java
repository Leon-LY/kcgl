package com.kcgl.module.item;

import com.kcgl.common.web.ApiResponse;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.auth.KcglUserDetails;
import com.kcgl.module.item.dto.CreateItemRequest;
import com.kcgl.module.item.dto.ItemResponse;
import com.kcgl.module.item.dto.VoidItemRequest;
import com.kcgl.module.itemcode.CreateItemCommand;
import com.kcgl.module.itemcode.ItemCodeService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 商品端点（M2-3 录入；M2-6 详情/作废。列表/编辑/回收站随后续里程碑接入）。
 * 录入/作废=E+；返回最终管理号（预览号仅为推算，以此为准）。
 */
@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final ItemCodeService itemCodeService;
    private final ItemService itemService;
    private final Clock clock;

    public ItemController(ItemCodeService itemCodeService, ItemService itemService, Clock clock) {
        this.itemCodeService = itemCodeService;
        this.itemService = itemService;
        this.clock = clock;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<ItemResponse> create(@Valid @RequestBody CreateItemRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        LocalDate today = LocalDate.now(clock);
        if (req.buyDate().isAfter(today)) {
            throw new BizException(ErrorCode.VALIDATION, "落札日に未来の日付は指定できません");
        }
        if (req.photoDate() != null && req.photoDate().isAfter(today)) {
            throw new BizException(ErrorCode.VALIDATION, "撮影日に未来の日付は指定できません");
        }
        CreateItemCommand cmd = new CreateItemCommand(
                req.clientReqId(), req.reEntryOf(), req.venueId(), req.buyDate(), req.photoDate(),
                req.purchasePrice(), req.fee(), req.shippingFee(), req.tax(),
                req.warehouse(), req.shelfNo(), req.warehouseInDate(), req.groupNo(), req.remark(),
                req.itemName(), req.category(), req.authorKiln(), req.sizeText(),
                req.weightG(), req.salesChannel(),
                operator.getUserId(), operator.getDisplayName());
        return ApiResponse.ok(ItemResponse.from(itemCodeService.create(cmd)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public ApiResponse<ItemResponse> get(@PathVariable long id) {
        return ApiResponse.ok(ItemResponse.from(itemService.getById(id)));
    }

    /** 作废（冻结，禁一切变动；重录走 POST /api/items + reEntryOf）。 */
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<ItemResponse> voidItem(@PathVariable long id,
            @Valid @RequestBody VoidItemRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(ItemResponse.from(
                itemService.voidItem(id, req, operator.getUserId(), operator.getDisplayName())));
    }
}
