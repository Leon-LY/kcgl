package com.kcgl.common.obs;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 入口 traceId（docs/01 9.3）：每请求生成 8 位十六进制 ID 入 MDC（所有日志行自动携带）
 * 并回写 X-Trace-Id 响应头。500 时的 errorId 直接复用 traceId（GlobalExceptionHandler），
 * 形成「用户报 errorId → 按该 ID 检索日志全行」的闭环。
 * 容器级过滤器且优先级最高：先于 Security 链（-100）与业务过滤器。
 * 不接受外部传入的 traceId（上游头不可信，防日志注入）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_TRACE_ID = "traceId";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        String traceId = HexFormat.of().formatHex(bytes);
        MDC.put(MDC_TRACE_ID, traceId);
        response.setHeader(HEADER_TRACE_ID, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID); // 线程池复用，必须清理防串号
        }
    }
}
