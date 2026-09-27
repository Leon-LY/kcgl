package com.kcgl.module.auth;

import tools.jackson.databind.ObjectMapper;
import com.kcgl.common.web.ApiResponse;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.auth.dto.MeResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 登录/登出/未认证/无权限 的 JSON 响应处理（SPA 场景，不渲染重定向页）。
 * 防枚举：未知用户名与密码错误返回字节级相同的 401 JSON。
 */
@Component
public class LoginJsonHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final LoginLockService lockService;

    public LoginJsonHandlers(ObjectMapper objectMapper, LoginLockService lockService) {
        this.objectMapper = objectMapper;
        this.lockService = lockService;
    }

    /** formLogin 成功：清计数 + 返回用户信息。 */
    public void onSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        KcglUserDetails user = (KcglUserDetails) authentication.getPrincipal();
        lockService.recordSuccess(user.getUsername(), request.getRemoteAddr());
        write(response, 200, ApiResponse.ok(MeResponse.from(user)));
    }

    /** formLogin 失败：锁定 423 / 停用 401 / 凭据错误 401（计数）。 */
    public void onFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String username = request.getParameter("username");
        String ip = request.getRemoteAddr();
        if (exception instanceof org.springframework.security.authentication.LockedException) {
            Optional<Duration> remaining = lockService.dbRemaining(username)
                    .or(() -> lockService.inMemoryRemaining(username, ip));
            long minutes = remaining.map(d -> Math.max(1, (d.toSeconds() + 59) / 60)).orElse(1L);
            write(response, 423, ApiResponse.error(ErrorCode.ACCOUNT_LOCKED, Map.of("remainingMinutes", minutes)));
            return;
        }
        if (exception instanceof org.springframework.security.authentication.DisabledException) {
            write(response, 401, ApiResponse.error(ErrorCode.ACCOUNT_DISABLED));
            return;
        }
        if (username != null && !username.isBlank()) {
            lockService.recordFailure(username, ip);
        }
        write(response, 401, ApiResponse.error(ErrorCode.BAD_CREDENTIALS));
    }

    /** 登出成功。 */
    public void onLogout(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        write(response, 200, ApiResponse.ok());
    }

    /** 未认证访问受保护端点：401 JSON（SPA 跳登录，不 302）。 */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        write(response, 401, ApiResponse.error(ErrorCode.UNAUTHENTICATED));
    }

    /** 已认证但无权限：403 JSON。 */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            org.springframework.security.access.AccessDeniedException e) throws IOException {
        write(response, 403, ApiResponse.error(ErrorCode.FORBIDDEN));
    }

    private void write(HttpServletResponse response, int status, ApiResponse<?> body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
