package com.kcgl.module.dict;

import com.kcgl.common.sse.SseHub;
import com.kcgl.common.sse.SyncEvent;
import com.kcgl.common.web.ApiResponse;
import com.kcgl.module.dict.dto.PriceBandResponse;
import com.kcgl.module.dict.dto.PriceBandUpsertRequest;
import com.kcgl.module.dict.dto.StatusRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 价格档位端点（docs/01 六节）：查询与匹配全员；增改停用=管理员。
 * match 供录入页档位字母预览与校验。变更后广播 DICT（提交后，同 VenueController）。
 */
@RestController
@RequestMapping("/api/price-bands")
public class PriceBandController {

    private final PriceBandService priceBandService;
    private final SseHub sseHub;

    public PriceBandController(PriceBandService priceBandService, SseHub sseHub) {
        this.priceBandService = priceBandService;
        this.sseHub = sseHub;
    }

    @GetMapping
    public ApiResponse<List<PriceBandResponse>> list() {
        return ApiResponse.ok(priceBandService.list());
    }

    @GetMapping("/match")
    public ApiResponse<PriceBandResponse> match(@RequestParam long price) {
        return ApiResponse.ok(priceBandService.match(price));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PriceBandResponse> create(@Valid @RequestBody PriceBandUpsertRequest req) {
        PriceBandResponse band = priceBandService.create(req);
        sseHub.broadcast(SyncEvent.TYPE_DICT, "band:" + band.code(), null);
        return ApiResponse.ok(band);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PriceBandResponse> update(@PathVariable Long id, @Valid @RequestBody PriceBandUpsertRequest req) {
        PriceBandResponse band = priceBandService.update(id, req);
        sseHub.broadcast(SyncEvent.TYPE_DICT, "band:" + band.code(), null);
        return ApiResponse.ok(band);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PriceBandResponse> updateStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest req) {
        PriceBandResponse band = priceBandService.updateStatus(id, req.enabled() == 1);
        sseHub.broadcast(SyncEvent.TYPE_DICT, "band:" + band.code(), null);
        return ApiResponse.ok(band);
    }
}
