package com.kcgl.module.user;

import com.kcgl.common.web.ApiResponse;
import com.kcgl.common.web.PageResponse;
import com.kcgl.module.auth.KcglUserDetails;
import com.kcgl.module.user.dto.CreateUserRequest;
import com.kcgl.module.user.dto.PasswordResetResponse;
import com.kcgl.module.user.dto.UpdateUserRequest;
import com.kcgl.module.user.dto.UserResponse;
import com.kcgl.module.user.dto.UserStatusRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号管理端点（docs/01 六节 users 模块，角色 A）。
 * URL 级 RBAC：/api/users/** 仅 ADMIN（SecurityConfig）；分页 size 上限 100。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ApiResponse<PageResponse<UserResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(userService.page(Math.max(1, page), Math.min(MAX_PAGE_SIZE, Math.max(1, size))));
    }

    @PostMapping
    public ApiResponse<UserResponse> create(@Valid @RequestBody CreateUserRequest req) {
        return ApiResponse.ok(userService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest req) {
        return ApiResponse.ok(userService.update(id, req));
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<UserResponse> updateStatus(@PathVariable Long id,
            @Valid @RequestBody UserStatusRequest req,
            @AuthenticationPrincipal KcglUserDetails operator) {
        return ApiResponse.ok(userService.updateStatus(id, req.enabled(), operator.getUserId()));
    }

    @PatchMapping("/{id}/unlock")
    public ApiResponse<UserResponse> unlock(@PathVariable Long id) {
        return ApiResponse.ok(userService.unlock(id));
    }

    @PostMapping("/{id}/password-reset")
    public ApiResponse<PasswordResetResponse> resetPassword(@PathVariable Long id) {
        return ApiResponse.ok(userService.resetPassword(id));
    }
}
