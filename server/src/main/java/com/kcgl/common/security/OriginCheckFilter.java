package com.kcgl.common.security;

import tools.jackson.databind.ObjectMapper;
import com.kcgl.common.web.ApiResponse;
import com.kcgl.common.web.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * CSRF 双保险之一（docs/01 八节）：非幂等请求携带 Origin 且不在白名单 → 403。
 * SameSite=Strict 是主防线，本过滤器兜住「目标站本身跨子域」与配置失误两类残留面。
 * Origin 缺失（非浏览器客户端）放行——同源部署下浏览器 POST 一定带 Origin。
 */
public class OriginCheckFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final Set<String> allowedOrigins;
    private final ObjectMapper objectMapper;

    public OriginCheckFilter(Set<String> allowedOrigins, ObjectMapper objectMapper) {
        this.allowedOrigins = allowedOrigins;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!SAFE_METHODS.contains(request.getMethod())) {
            String origin = request.getHeader("Origin");
            if (origin != null && !origin.isBlank() && !allowedOrigins.contains(origin)) {
                response.setStatus(403);
                response.setContentType("application/json;charset=UTF-8");
                response.setCharacterEncoding("UTF-8");
                objectMapper.writeValue(response.getWriter(), ApiResponse.error(ErrorCode.BAD_ORIGIN));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
