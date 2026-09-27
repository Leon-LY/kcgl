package com.kcgl.module.user.dto;

import com.kcgl.module.user.SysUserEntity;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 账号视图：绝不含 passwordHash（创建/列表/更新统一出口）。
 * locked 为即时计算值（locked_until 晚于当前时刻），非列直传。
 */
public record UserResponse(
        Long id,
        String username,
        String displayName,
        int role,
        String locale,
        boolean enabled,
        boolean mustChangePwd,
        boolean locked,
        LocalDateTime lockedUntil,
        int failedAttempts,
        LocalDateTime lastLoginAt) {

    public static UserResponse from(SysUserEntity entity, Clock clock) {
        boolean locked = entity.getLockedUntil() != null
                && entity.getLockedUntil().isAfter(LocalDateTime.now(clock));
        return new UserResponse(
                entity.getId(),
                entity.getUsername(),
                entity.getDisplayName(),
                entity.getRole(),
                entity.getLocale(),
                entity.getEnabled() != null && entity.getEnabled() == 1,
                entity.getMustChangePwd() != null && entity.getMustChangePwd() == 1,
                locked,
                entity.getLockedUntil(),
                entity.getFailedAttempts() == null ? 0 : entity.getFailedAttempts(),
                entity.getLastLoginAt());
    }
}
