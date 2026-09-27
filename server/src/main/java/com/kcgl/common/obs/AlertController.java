package com.kcgl.common.obs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kcgl.common.web.ApiResponse;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.common.web.PageResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 告警查询与已读（docs/01 六节，角色 A——URL 规则已锁 GET /api/alerts 与 PATCH read）。
 */
@RestController
public class AlertController {

    private final SysAlertMapper mapper;
    private final Clock clock;

    public AlertController(SysAlertMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @GetMapping("/api/alerts")
    public ApiResponse<PageResponse<SysAlertEntity>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        Page<SysAlertEntity> result = mapper.selectPage(new Page<>(Math.max(1, page), Math.min(100, size)),
                new LambdaQueryWrapper<SysAlertEntity>()
                        .orderByAsc(SysAlertEntity::getStatus)      // 开启态在前
                        .orderByDesc(SysAlertEntity::getCreatedAt));
        return ApiResponse.ok(PageResponse.of(result.getRecords(), result.getTotal(), page, size));
    }

    @PatchMapping("/api/alerts/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id,
            @AuthenticationPrincipal com.kcgl.module.auth.KcglUserDetails operator) {
        SysAlertEntity alert = mapper.selectById(id);
        if (alert == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        alert.setStatus(1);
        alert.setReadBy(operator.getUserId());
        alert.setReadAt(LocalDateTime.now(clock));
        mapper.updateById(alert);
        return ApiResponse.ok();
    }
}
