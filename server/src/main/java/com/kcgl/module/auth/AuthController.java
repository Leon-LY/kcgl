package com.kcgl.module.auth;

import com.kcgl.common.web.ApiResponse;
import com.kcgl.module.auth.dto.ChangeLocaleRequest;
import com.kcgl.module.auth.dto.ChangePasswordRequest;
import com.kcgl.module.auth.dto.MeResponse;
import com.kcgl.module.user.SysUserEntity;
import com.kcgl.module.user.SysUserMapper;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话自助端点：me / 改自己的密码 / 改界面语言（docs/01 六节认证模块）。
 * 登录与登出由 Security 过滤器链处理（formLogin / logout），不在控制器层；
 * 写操作委托 AuthService（事务与审计在其中）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SysUserMapper mapper;
    private final AuthService authService;

    public AuthController(SysUserMapper mapper, AuthService authService) {
        this.mapper = mapper;
        this.authService = authService;
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal KcglUserDetails user) {
        // 以 DB 现值返回（locale/改密标记可能在别的端点被更新过）；行被删则回退会话快照
        SysUserEntity entity = mapper.selectById(user.getUserId());
        return ApiResponse.ok(entity != null
                ? new MeResponse(entity.getUsername(), entity.getDisplayName(), entity.getRole(),
                        entity.getLocale(), entity.getMustChangePwd() != null && entity.getMustChangePwd() == 1)
                : MeResponse.from(user));
    }

    @PutMapping("/me/password")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal KcglUserDetails user,
            @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(user, request);
        return ApiResponse.ok();
    }

    @PutMapping("/me/locale")
    public ApiResponse<Void> changeLocale(@AuthenticationPrincipal KcglUserDetails user,
            @Valid @RequestBody ChangeLocaleRequest request) {
        authService.changeLocale(user, request);
        return ApiResponse.ok();
    }
}
