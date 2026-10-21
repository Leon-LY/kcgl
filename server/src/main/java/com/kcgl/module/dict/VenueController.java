package com.kcgl.module.dict;

import com.kcgl.common.sse.SseHub;
import com.kcgl.common.sse.SyncEvent;
import com.kcgl.common.web.ApiResponse;
import com.kcgl.module.dict.dto.StatusRequest;
import com.kcgl.module.dict.dto.VenueCreateRequest;
import com.kcgl.module.dict.dto.VenueResponse;
import com.kcgl.module.dict.dto.VenueUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 会场字典端点（docs/01 六节）：查询全员；创建/编辑（改名/改码）=可编辑及以上（现场自救）；
 * 停用/删除=管理员。删除仅限无商品引用（D-166）。变更后广播 DICT——他端录入页的
 * 会场下拉靠 dicts 缓存失效重取（服务 @Transactional 返回=已提交，控制器层=天然提交后广播点）。
 */
@RestController
@RequestMapping("/api/venues")
public class VenueController {

    private final VenueService venueService;
    private final SseHub sseHub;

    public VenueController(VenueService venueService, SseHub sseHub) {
        this.venueService = venueService;
        this.sseHub = sseHub;
    }

    @GetMapping
    public ApiResponse<List<VenueResponse>> list(@RequestParam(required = false) Boolean enabled) {
        return ApiResponse.ok(venueService.list(enabled));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<VenueResponse> create(@Valid @RequestBody VenueCreateRequest req) {
        VenueResponse venue = venueService.create(req);
        sseHub.broadcast(SyncEvent.TYPE_DICT, "venue:" + venue.code(), null);
        return ApiResponse.ok(venue);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<VenueResponse> update(@PathVariable Long id, @Valid @RequestBody VenueUpdateRequest req) {
        VenueResponse venue = venueService.update(id, req);
        sseHub.broadcast(SyncEvent.TYPE_DICT, "venue:" + venue.code(), null);
        return ApiResponse.ok(venue);
    }

    /** 删除（D-166）：管理员专属；有商品引用时 409017 引导停用。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        VenueResponse venue = venueService.delete(id);
        sseHub.broadcast(SyncEvent.TYPE_DICT, "venue:" + venue.code(), null);
        return ApiResponse.ok();
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<VenueResponse> updateStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest req) {
        VenueResponse venue = venueService.updateStatus(id, req.enabled() == 1);
        sseHub.broadcast(SyncEvent.TYPE_DICT, "venue:" + venue.code(), null);
        return ApiResponse.ok(venue);
    }
}
