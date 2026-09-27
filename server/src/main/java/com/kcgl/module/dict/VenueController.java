package com.kcgl.module.dict;

import com.kcgl.common.web.ApiResponse;
import com.kcgl.module.dict.dto.StatusRequest;
import com.kcgl.module.dict.dto.VenueCreateRequest;
import com.kcgl.module.dict.dto.VenueRenameRequest;
import com.kcgl.module.dict.dto.VenueResponse;
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
 * 会场字典端点（docs/01 六节）：查询全员；创建/改名=可编辑及以上（现场自救）；
 * 停用=管理员。只停用不物理删。
 */
@RestController
@RequestMapping("/api/venues")
public class VenueController {

    private final VenueService venueService;

    public VenueController(VenueService venueService) {
        this.venueService = venueService;
    }

    @GetMapping
    public ApiResponse<List<VenueResponse>> list(@RequestParam(required = false) Boolean enabled) {
        return ApiResponse.ok(venueService.list(enabled));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<VenueResponse> create(@Valid @RequestBody VenueCreateRequest req) {
        return ApiResponse.ok(venueService.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<VenueResponse> rename(@PathVariable Long id, @Valid @RequestBody VenueRenameRequest req) {
        return ApiResponse.ok(venueService.rename(id, req));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<VenueResponse> updateStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest req) {
        return ApiResponse.ok(venueService.updateStatus(id, req.enabled() == 1));
    }
}
