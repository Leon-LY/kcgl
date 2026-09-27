package com.kcgl.module.dict;

import com.kcgl.common.web.ApiResponse;
import com.kcgl.module.dict.dto.YearCodeResponse;
import com.kcgl.module.dict.dto.YearCodeUpsertRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 年代号端点（docs/01 六节）：查询全员（录入页年代号展示）；增改=管理员。 */
@RestController
@RequestMapping("/api/year-codes")
public class YearCodeController {

    private final YearCodeService yearCodeService;

    public YearCodeController(YearCodeService yearCodeService) {
        this.yearCodeService = yearCodeService;
    }

    @GetMapping
    public ApiResponse<List<YearCodeResponse>> list() {
        return ApiResponse.ok(yearCodeService.list());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<YearCodeResponse> create(@Valid @RequestBody YearCodeUpsertRequest req) {
        return ApiResponse.ok(yearCodeService.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<YearCodeResponse> update(@PathVariable Long id, @Valid @RequestBody YearCodeUpsertRequest req) {
        return ApiResponse.ok(yearCodeService.update(id, req));
    }
}
