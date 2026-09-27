package com.kcgl.common.security;

import com.kcgl.module.user.SysUserEntity;

import java.time.LocalDateTime;

/**
 * 账号即死判定（D-024 唯一口径）：不存在/停用/锁定未过期。
 * AccountStatusFilter（请求路径每请求校验）与 SseHub 心跳（长连接无后续请求）共用——
 * 两处口径分叉会造成「请求被拒但 SSE 仍推送」的窗口。
 */
public final class AccountStatuses {

    private AccountStatuses() {
    }

    public static boolean dead(SysUserEntity user, LocalDateTime now) {
        return user == null
                || user.getEnabled() == null || user.getEnabled() != 1
                || (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now));
    }
}
