package com.kcgl.module.itemcode;

import com.kcgl.common.web.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 管理号端点：预览=录入表单流水号推算（E+；档位字母前端本地缓存即时算，此处为
 * 档位+流水两级合一的校正请求）。预览≠保留（docs/01 7.1）。
 */
@RestController
@RequestMapping("/api/item-codes")
public class ItemCodeController {

    private final ItemCodeTxService txService;

    public ItemCodeController(ItemCodeTxService txService) {
        this.txService = txService;
    }

    @GetMapping("/preview")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<ItemCodePreviewResponse> preview(
            @RequestParam long venueId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate buyDate,
            @RequestParam long price) {
        return ApiResponse.ok(txService.preview(venueId, buyDate, price));
    }
}
