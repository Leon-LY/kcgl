package com.kcgl.module.auth.dto;

import com.kcgl.module.auth.KcglUserDetails;

/**
 * 当前会话用户信息（登录成功响应与 GET /api/auth/me 共用）。
 * id=用户主键：前端 SSE 回声抑制用（自己操作的广播 operatorId===me.id 不触发失效，D-070）。
 */
public record MeResponse(Long id, String username, String displayName, int role, String locale, boolean mustChangePwd) {

    public static MeResponse from(KcglUserDetails user) {
        return new MeResponse(user.getUserId(), user.getUsername(), user.getDisplayName(), user.getRole().id(),
                user.getLocale(), user.isMustChangePwd());
    }
}
