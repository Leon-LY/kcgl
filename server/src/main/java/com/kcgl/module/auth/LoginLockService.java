package com.kcgl.module.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kcgl.module.user.SysUserEntity;
import com.kcgl.module.user.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败锁定（docs/01 八节防爆破）：(账号+IP) 维度，15 分钟窗口内连续 5 次失败 → 锁 15 分钟。
 * 纯账号维度可被武器化为全员 DoS（任何人输错 5 次即锁死他人账号），故加 IP 维度；
 * 跨 IP 撞库的渐进延迟留 observability 切片（WARN 审计日志）。
 * 已知账号的锁落 DB（locked_until，跨重启生效、管理端可解锁）；未知账号仅内存锁（防枚举+节流）。
 */
@Service
public class LoginLockService {

    static final int MAX_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    static final Duration WINDOW = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(LoginLockService.class);

    record FailState(int count, Instant windowStart, Instant lockedUntil) {
    }

    private final ConcurrentHashMap<String, FailState> failures = new ConcurrentHashMap<>();

    private final SysUserMapper mapper;
    private final Clock clock;

    public LoginLockService(SysUserMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 登录失败计数：达到阈值后写 DB 锁（已知账号）或保留内存锁（未知账号）。 */
    public void recordFailure(String username, String ip) {
        String key = key(username, ip);
        FailState state = failures.compute(key, (k, prev) -> advance(prev, clock.instant()));
        if (state.lockedUntil() != null) {
            int updated = mapper.update(null, Wrappers.<SysUserEntity>lambdaUpdate()
                    .eq(SysUserEntity::getUsername, username)
                    .set(SysUserEntity::getLockedUntil, toLocal(state.lockedUntil()))
                    .set(SysUserEntity::getFailedAttempts, state.count()));
            if (updated > 0) {
                // DB 锁已生效（KcglUserDetails 前置检查接管），内存计数让位
                failures.remove(key);
            } else {
                log.warn("未知账号多次登录失败（内存锁定 15 分钟）：username={}, ip={}", username, ip);
            }
        } else {
            mapper.update(null, Wrappers.<SysUserEntity>lambdaUpdate()
                    .eq(SysUserEntity::getUsername, username)
                    .set(SysUserEntity::getFailedAttempts, state.count()));
        }
    }

    /** 登录成功：清当前 (账号+IP) 计数并复位 DB failed_attempts / 记录 last_login_at。 */
    public void recordSuccess(String username, String ip) {
        failures.remove(key(username, ip));
        mapper.update(null, Wrappers.<SysUserEntity>lambdaUpdate()
                .eq(SysUserEntity::getUsername, username)
                .set(SysUserEntity::getFailedAttempts, 0)
                .set(SysUserEntity::getLastLoginAt, LocalDateTime.now(clock)));
    }

    /** 内存锁剩余时长（未知账号路径 + 认证过滤器前置拦截）。 */
    public Optional<Duration> inMemoryRemaining(String username, String ip) {
        FailState state = failures.get(key(username, ip));
        if (state == null || state.lockedUntil() == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return state.lockedUntil().isAfter(now)
                ? Optional.of(Duration.between(now, state.lockedUntil()))
                : Optional.empty();
    }

    /** DB 锁剩余时长（已知账号路径，LockedException 响应用）。 */
    public Optional<Duration> dbRemaining(String username) {
        if (username == null) {
            return Optional.empty();
        }
        SysUserEntity entity = mapper.selectOne(
                Wrappers.<SysUserEntity>lambdaQuery().eq(SysUserEntity::getUsername, username));
        if (entity == null || entity.getLockedUntil() == null) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return entity.getLockedUntil().isAfter(now)
                ? Optional.of(Duration.between(now, entity.getLockedUntil()))
                : Optional.empty();
    }

    /** 仅供测试隔离使用：清空内存计数（每个用例独立起点，DB 状态由用例自行 DELETE 复位）。 */
    public void reset() {
        failures.clear();
    }

    private FailState advance(FailState prev, Instant now) {
        if (prev == null) {
            return new FailState(1, now, null);
        }
        if (prev.lockedUntil() != null) {
            // 锁定期间的失败不应到达这里（认证过滤器先拦 423）；防御性返回不续期
            return prev.lockedUntil().isAfter(now) ? prev : new FailState(1, now, null);
        }
        if (prev.windowStart().plus(WINDOW).isBefore(now)) {
            return new FailState(1, now, null); // 窗口滑出，重新计数
        }
        int next = prev.count() + 1;
        return next >= MAX_ATTEMPTS
                ? new FailState(next, prev.windowStart(), now.plus(LOCK_DURATION))
                : new FailState(next, prev.windowStart(), null);
    }

    private String key(String username, String ip) {
        return username + "|" + ip;
    }

    private LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, clock.getZone());
    }
}
