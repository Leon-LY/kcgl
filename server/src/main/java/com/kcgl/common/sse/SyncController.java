package com.kcgl.common.sse;

import com.kcgl.module.auth.KcglUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 实时同步端点（docs/01 7.6 唯一定义）：GET /api/sync/events 建立事件流。
 * 连接即 HELLO；生命周期由 SseHub 闭合——登出/12h 超时即时关（会话销毁事件），
 * 停用/并发被踢心跳周期内补杀（长连接无后续请求，请求路径过滤器够不着）。
 */
@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SseHub sseHub;

    public SyncController(SseHub sseHub) {
        this.sseHub = sseHub;
    }

    @GetMapping("/events")
    public SseEmitter events(@AuthenticationPrincipal KcglUserDetails user,
            HttpServletRequest request, HttpServletResponse response) {
        // X-Accel-Buffering：nginx 逐条转发不缓冲（代理段配置之外的响应头双保险）；
        // no-store：事件流过任何代理/网关都不许缓存
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-store");
        return sseHub.register(user.getUserId(), request.getSession().getId());
    }
}
