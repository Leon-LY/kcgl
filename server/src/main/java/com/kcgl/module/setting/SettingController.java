package com.kcgl.module.setting;

import com.kcgl.common.sse.SseHub;
import com.kcgl.common.sse.SyncEvent;
import com.kcgl.common.web.ApiResponse;
import com.kcgl.module.auth.KcglUserDetails;
import com.kcgl.module.setting.dto.UpdateSettingRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统设置端点（docs/01 六节）：GET 全员（打印页读标签尺寸、列表读滞销阈值）；
 * PUT 单键=管理员。变更后广播 SETTING 携 operatorId——操作者自己的页面由
 * PUT 响应驱动（回声抑制 D-070），他端 settings 缓存失效重取。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingController {

    private final SettingService settingService;
    private final SseHub sseHub;

    public SettingController(SettingService settingService, SseHub sseHub) {
        this.settingService = settingService;
        this.sseHub = sseHub;
    }

    @GetMapping
    public ApiResponse<AppSettings> get() {
        return ApiResponse.ok(settingService.appSettings());
    }

    @PutMapping("/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AppSettings> update(@PathVariable String key,
            @Valid @RequestBody UpdateSettingRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        AppSettings updated = settingService.updateSetting(key, req.value(), operator.getUserId());
        sseHub.broadcast(SyncEvent.TYPE_SETTING, "setting:" + key, operator.getUserId());
        return ApiResponse.ok(updated);
    }
}
