package com.kcgl.module.auth.dto;

import com.kcgl.module.auth.KcglUserDetails;

/**
 * 当前会话用户信息（登录成功响应与 GET /api/auth/me 共用）。
 */
public record MeResponse(String username, String displayName, int role, String locale, boolean mustChangePwd) {

    public static MeResponse from(KcglUserDetails user) {
        return new MeResponse(user.getUsername(), user.getDisplayName(), user.getRole().id(),
                user.getLocale(), user.isMustChangePwd());
    }
}
