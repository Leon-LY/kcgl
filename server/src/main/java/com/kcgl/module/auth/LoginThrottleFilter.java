package com.kcgl.module.auth;

import tools.jackson.databind.ObjectMapper;
import com.kcgl.common.web.ApiResponse;
import com.kcgl.common.web.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * 登录前置节流：内存锁命中的 (账号+IP) 在到达认证器之前直接 423，
 * 未知账号的撞库不会消耗 BCrypt 计算，也不再累计计数。
 * 挂在 UsernamePasswordAuthenticationFilter 之前（SecurityConfig 内组装，非容器级 bean）。
 *
 * 路径匹配必须用与 Security 过滤链同源的 PathPatternRequestMatcher（解码后匹配）——
 * 禁止对 getRequestURI() 做原始字符串比较：URL 编码变体（如 /api/auth/%6Cogin）
 * 会被认证过滤器按解码路径正常处理，而原始比较不命中 → 内存锁被绕过（regression 用例覆盖）。
 */
public class LoginThrottleFilter extends OncePerRequestFilter {

    private static final PathPatternRequestMatcher LOGIN_MATCHER =
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/login");

    private final LoginLockService lockService;
    private final ObjectMapper objectMapper;

    public LoginThrottleFilter(LoginLockService lockService, ObjectMapper objectMapper) {
        this.lockService = lockService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (LOGIN_MATCHER.matches(request)) {
            String username = request.getParameter("username");
            if (username != null) {
                var remaining = lockService.inMemoryRemaining(username, request.getRemoteAddr());
                if (remaining.isPresent()) {
                    long minutes = Math.max(1, (remaining.get().toSeconds() + 59) / 60);
                    response.setStatus(423);
                    response.setContentType("application/json;charset=UTF-8");
                    response.setCharacterEncoding("UTF-8");
                    objectMapper.writeValue(response.getWriter(),
                            ApiResponse.error(ErrorCode.ACCOUNT_LOCKED, Map.of("remainingMinutes", minutes)));
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
