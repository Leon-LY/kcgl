package com.kcgl.module.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kcgl.common.web.ApiResponse;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.auth.dto.ChangeLocaleRequest;
import com.kcgl.module.auth.dto.ChangePasswordRequest;
import com.kcgl.module.auth.dto.MeResponse;
import com.kcgl.module.user.SysUserEntity;
import com.kcgl.module.user.SysUserMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 会话自助端点：me / 改自己的密码 / 改界面语言（docs/01 六节认证模块）。
 * 登录与登出由 Security 过滤器链处理（formLogin / logout），不在控制器层。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SysUserMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AuthController(SysUserMapper mapper, PasswordEncoder passwordEncoder, Clock clock) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
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
    public ResponseEntity<ApiResponse<Void>> changePassword(@AuthenticationPrincipal KcglUserDetails user,
            @Valid @RequestBody ChangePasswordRequest request) {
        SysUserEntity entity = mapper.selectById(user.getUserId());
        if (entity == null || !passwordEncoder.matches(request.oldPassword(), entity.getPasswordHash())) {
            return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.OLD_PASSWORD_MISMATCH));
        }
        if (request.newPassword().equals(request.oldPassword())) {
            return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.PASSWORD_POLICY));
        }
        mapper.update(null, Wrappers.<SysUserEntity>lambdaUpdate()
                .eq(SysUserEntity::getId, user.getUserId())
                .set(SysUserEntity::getPasswordHash, passwordEncoder.encode(request.newPassword()))
                .set(SysUserEntity::getMustChangePwd, 0)
                .set(SysUserEntity::getUpdatedAt, LocalDateTime.now(clock)));
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @PutMapping("/me/locale")
    public ResponseEntity<ApiResponse<Void>> changeLocale(@AuthenticationPrincipal KcglUserDetails user,
            @Valid @RequestBody ChangeLocaleRequest request) {
        mapper.update(null, Wrappers.<SysUserEntity>lambdaUpdate()
                .eq(SysUserEntity::getId, user.getUserId())
                .set(SysUserEntity::getLocale, request.locale())
                .set(SysUserEntity::getUpdatedAt, LocalDateTime.now(clock)));
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
