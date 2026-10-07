package com.kcgl.module.item;

import com.kcgl.common.web.ApiResponse;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.auth.KcglUserDetails;
import com.kcgl.module.item.dto.CreateItemRequest;
import com.kcgl.module.item.dto.ItemByCodeResponse;
import com.kcgl.module.item.dto.ItemLedgerListResponse;
import com.kcgl.module.item.dto.ItemListResponse;
import com.kcgl.module.item.dto.ItemResponse;
import com.kcgl.module.item.dto.ItemSearchResponse;
import com.kcgl.module.item.dto.RecycleActionRequest;
import com.kcgl.module.item.dto.RecycleBatchRequest;
import com.kcgl.module.item.dto.RecycleBatchResponse;
import com.kcgl.module.item.dto.RecycleBinResponse;
import com.kcgl.module.item.dto.TodaySessionResponse;
import com.kcgl.module.item.dto.UpdateItemRequest;
import com.kcgl.module.item.dto.VoidItemRequest;
import com.kcgl.module.item.dto.YahooListingListResponse;
import com.kcgl.module.inventory.InventoryActionService;
import com.kcgl.module.inventory.dto.AdjustRequest;
import com.kcgl.module.itemcode.CreateItemCommand;
import com.kcgl.module.itemcode.ItemCodeService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 商品端点（M2-3 录入；M2-6 详情/作废；M2-7 打印列表；M5-① 搜索/编辑/回收站/子资源历史）。
 * 录入/作废/编辑=E+；软删/恢复/回收站=A；搜索/详情/流水/出品记录=全员；
 * 返回最终管理号（预览号仅为推算，以此为准）。
 */
@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final ItemCodeService itemCodeService;
    private final ItemService itemService;
    private final ItemSearchService itemSearchService;
    private final ItemEditService itemEditService;
    private final RecycleService recycleService;
    private final ItemHistoryService itemHistoryService;
    private final InventoryActionService actionService;
    private final Clock clock;

    public ItemController(ItemCodeService itemCodeService, ItemService itemService,
            ItemSearchService itemSearchService, ItemEditService itemEditService,
            RecycleService recycleService, ItemHistoryService itemHistoryService,
            InventoryActionService actionService, Clock clock) {
        this.itemCodeService = itemCodeService;
        this.itemService = itemService;
        this.itemSearchService = itemSearchService;
        this.itemEditService = itemEditService;
        this.recycleService = recycleService;
        this.itemHistoryService = itemHistoryService;
        this.actionService = actionService;
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

    /** 扫码定位（M3-⑤）：管理号命中即返回（含作废/软删件，标志驱动前端禁操作）；404=号不存在。 */
    @GetMapping("/by-code/{code}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public ApiResponse<ItemByCodeResponse> byCode(@PathVariable String code) {
        return ApiResponse.ok(itemService.byCode(code));
    }

    /** 打印页列表（M2-7 日期/会场；M2-9 code=管理号单票再印刷，优先于日期条件）；作废/软删件不出标签。 */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public ApiResponse<ItemListResponse> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(required = false) Long venueId,
            @RequestParam(required = false) String code,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.ok(
                itemService.listForPrint(createdFrom, createdTo, venueId, code, page, size));
    }

    /** 本日录入会话（M2-8b）：我的当天录入（含作废），现场誊写与收工对数。 */
    @GetMapping("/today-session")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public ApiResponse<TodaySessionResponse> todaySession(
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(itemService.todaySession(operator.getUserId()));
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

    /** 全局搜索（M5-①，D-061/D-062/D-065）：独立端点，打印契约零改动；作废/软删件排除。 */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public ApiResponse<ItemSearchResponse> search(
            @RequestParam(required = false) String kw,
            @RequestParam(required = false) Integer warehouse,
            @RequestParam(required = false) Integer stockStatus,
            @RequestParam(required = false) Integer saleStatus,
            @RequestParam(required = false) Long venueId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate buyDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate buyDateTo,
            @RequestParam(required = false) Integer warnLevel,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.ok(itemSearchService.search(kw, warehouse, stockStatus, saleStatus,
                venueId, buyDateFrom, buyDateTo, warnLevel, page, size));
    }

    /** 编辑（M5-①，D-063/D-066）：snapshot 单模式——可改列不改号；非在途改仓值 409013 引导移动台账。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<ItemResponse> update(@PathVariable long id,
            @Valid @RequestBody UpdateItemRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        LocalDate today = LocalDate.now(clock);
        if (req.buyDate().isAfter(today)) {
            throw new BizException(ErrorCode.VALIDATION, "落札日に未来の日付は指定できません");
        }
        if (req.photoDate() != null && req.photoDate().isAfter(today)) {
            throw new BizException(ErrorCode.VALIDATION, "撮影日に未来の日付は指定できません");
        }
        return ApiResponse.ok(ItemResponse.from(
                itemEditService.update(id, req, operator.getUserId(), operator.getDisplayName())));
    }

    /**
     * 手工修正（D4，A-only，docs/01 7.2 矩阵「手工修正（A，须 reason）」）：管理员任意态
     * 覆盖库存/销售两轴，用于纠正状态机走不到的错误现态。绕开全部业务前提，故限管理员、
     * reason 必填、before/after 全量审计；不改仓库（改仓仍走 /inventory/transfer）。
     */
    @PostMapping("/{id}/adjust")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ItemResponse> adjust(@PathVariable long id,
            @Valid @RequestBody AdjustRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(ItemResponse.from(
                actionService.adjust(id, req, operator.getUserId(), operator.getDisplayName())));
    }

    /** 回收站软删（M5-①，D-064，A-only）：治理终态出口，作废件亦可入站。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ItemResponse> delete(@PathVariable long id,
            @Valid @RequestBody RecycleActionRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(ItemResponse.from(
                recycleService.delete(id, req, operator.getUserId(), operator.getDisplayName())));
    }

    /** 回收站恢复（M5-①，D-064，A-only）：stock_status 保序回软删前原值，作废标志不动。 */
    @PostMapping("/{id}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ItemResponse> restore(@PathVariable long id,
            @Valid @RequestBody RecycleActionRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(ItemResponse.from(
                recycleService.restore(id, req, operator.getUserId(), operator.getDisplayName())));
    }

    /** 回收站列表（M5-①，D-064，A-only）。 */
    @GetMapping("/recycle-bin")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<RecycleBinResponse> recycleBin(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(recycleService.bin(page, size));
    }

    /**
     * 批量软删（D-126，A-only）：商品一覧勾选多行后一次提交。
     * 路径是字面量而非 {@code /{id}/...}，与 {@code /recycle-bin} 同形；Spring 字面量优先于
     * {@code /{id}} 模板，且本类没有「单段 POST 模板」映射，不会与之争抢。
     * 逐件结果见 {@link RecycleBatchResponse}——批内某件失败不影响其余件。
     */
    @PostMapping("/recycle-delete")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<RecycleBatchResponse> recycleDelete(
            @Valid @RequestBody RecycleBatchRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(
                recycleService.deleteBatch(req, operator.getUserId(), operator.getDisplayName()));
    }

    /** 批量恢复（D-126，A-only）：回收站勾选多行后一次复原。 */
    @PostMapping("/recycle-restore")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<RecycleBatchResponse> recycleRestore(
            @Valid @RequestBody RecycleBatchRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(
                recycleService.restoreBatch(req, operator.getUserId(), operator.getDisplayName()));
    }

    /** 单件流水（M5-①，D-061）：全员可读，id 倒序。 */
    @GetMapping("/{id}/ledgers")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public ApiResponse<ItemLedgerListResponse> ledgers(@PathVariable long id) {
        return ApiResponse.ok(itemHistoryService.ledgers(id));
    }

    /** 单件雅虎出品记录（M5-①，D-061）：全员可读，listed_at 倒序。 */
    @GetMapping("/{id}/yahoo-listings")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")
    public ApiResponse<YahooListingListResponse> yahooListings(@PathVariable long id) {
        return ApiResponse.ok(itemHistoryService.yahooListings(id));
    }
}
