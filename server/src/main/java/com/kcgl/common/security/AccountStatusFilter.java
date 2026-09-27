package com.kcgl.common.security;

import tools.jackson.databind.ObjectMapper;
import com.kcgl.common.web.ApiResponse;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.auth.KcglUserDetails;
import com.kcgl.module.user.SysUserEntity;
import com.kcgl.module.user.SysUserMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 每请求账号状态即时校验（D-024）：登录时挡住 enabled/locked 不够——
 * 管理员停用恶意/离职账号后，其既有会话（最长 12h）必须立即失效。
 * 挂在 SecurityContextHolderFilter 之后：按 userId 主键查库（&lt;1ms，≤10 用户规模无压力），
 * enabled=0 或 DB 锁定未过期 → 清 SecurityContext + 会话中的 context 属性 + 401。
 * 匿名请求（登录端点）无认证体，天然跳过。
 */
public class AccountStatusFilter extends OncePerRequestFilter {

    private final SysUserMapper userMapper;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public AccountStatusFilter(SysUserMapper userMapper, Clock clock, ObjectMapper objectMapper) {
        this.userMapper = userMapper;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof KcglUserDetails details) {
            SysUserEntity fresh = userMapper.selectById(details.getUserId());
            boolean dead = fresh == null
                    || fresh.getEnabled() == null || fresh.getEnabled() != 1
                    || (fresh.getLockedUntil() != null && fresh.getLockedUntil().isAfter(LocalDateTime.now(clock)));
            if (dead) {
                SecurityContextHolder.clearContext();
                if (request.getSession(false) != null) {
                    request.getSession(false).removeAttribute("SPRING_SECURITY_CONTEXT");
                }
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.setCharacterEncoding("UTF-8");
                objectMapper.writeValue(response.getWriter(), ApiResponse.error(ErrorCode.UNAUTHENTICATED));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
