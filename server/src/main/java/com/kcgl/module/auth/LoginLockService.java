package com.kcgl.module.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kcgl.module.user.SysUserEntity;
import com.kcgl.module.user.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败锁定（docs/01 八节防爆破）：(账号+IP) 维度，15 分钟窗口内连续 5 次失败 → 锁 15 分钟。
 *
 * <p>纯账号维度可被武器化为全员 DoS（任何人输错 5 次即锁死他人账号），故**计数与内存锁均按
 * (账号+IP)**——单一源 IP 连错只锁它自己，不影响同账号的其他正常使用者（含正在工作的会话：
 * {@code AccountStatuses.dead} 会让账号锁立即踢掉活跃会话与 SSE 流）。
 *
 * <p>账号级持久锁（{@code sys_user.locked_until}）**仅在失败来自 ≥2 个不同源 IP 时升级写入**：
 * 多 IP 同账号才是真正需要账号级封堵的分布式撞库；此路径保留「跨重启生效 + 管理端可见/可解锁」
 * 语义（D-022/D-024）。跨 IP 撞库的渐进延迟另留 observability 切片（WARN 审计日志）。
 *
 * <p><b>源 IP 来自 {@code request.getRemoteAddr()}</b>，其正确性依赖
 * {@code server.forward-headers-strategy=native}（见 application.yml）：本系统唯一入口是 nginx，
 * 未启用转发头解析时 remoteAddr 恒为 nginx 容器 IP，上述整个 (账号+IP) 语义会退化成账号维度
 * 的 DoS——该配置项是本类前提，启用回归见 ForwardedClientIpTest。
 *
 * <p>三处与朴素实现不同、且各有回归门（AuthIntegrationTest / ForwardedClientIpTest）：
 * <ol>
 *   <li>账号名先做 {@link #canonicalUsername} 归一，对齐 MySQL {@code utf8mb4_0900_ai_ci} 的
 *       大小写/重音不敏感登录语义；不归一则 Admin/ADMIN/ádmin 各占一个计数桶，5 次预算被放大成
 *       5×变体数，节流形同虚设。</li>
 *   <li>计数表按账号分层（账号 → IP → 态），而非 "username|ip" 拼接键：登录端点不校验用户名形态，
 *       拼接键可被 {@code username="admin|x"} 伪造成他人计数项。</li>
 *   <li>计数表有容量上限 {@link #MAX_TRACKED_USERS}，超出即淘汰——登录端点无需认证，
 *       无上限的内存 Map 本身就是一条内存/CPU 耗尽路径。</li>
 * </ol>
 */
@Service
public class LoginLockService {

    static final int MAX_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    static final Duration WINDOW = Duration.ofMinutes(15);

    /** 升级为账号级 DB 锁所需的不同源 IP 数：单 IP 永不锁死整账号（防跨 IP DoS）。 */
    static final int ACCOUNT_LOCK_MIN_IPS = 2;

    /** 内存计数表容纳的账号数上限，超出即淘汰；不是业务阈值，仅作资源上限。 */
    static final int MAX_TRACKED_USERS = 8192;

    private static final Logger log = LoggerFactory.getLogger(LoginLockService.class);

    record FailState(int count, Instant windowStart, Instant lockedUntil) {
    }

    /**
     * 账号 → (源 IP → 失败态)。分层键：用户名与 IP 各自成键，互不污染（见类注释 2）。
     * 分层同时把升级判定与清扫的复杂度从「全表」收敛到「本账号的 IP 数」。
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, FailState>> failures =
            new ConcurrentHashMap<>();

    /**
     * DB 命中过的归一化账号名。仅当 {@code mapper.update} 影响行数 &gt; 0 时加入，
     * 故规模由 {@code sys_user} 行数决定——攻击者用伪造用户名无法使其膨胀。
     * 淘汰计数表时这批条目最后才被触及（真实账号的锁不该被洪泛挤掉）。
     */
    private final Set<String> knownUsers = ConcurrentHashMap.newKeySet();

    private final SysUserMapper mapper;
    private final Clock clock;

    public LoginLockService(SysUserMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * 登录失败计数：本 (账号+IP) 达阈值后由内存锁接管（{@link LoginThrottleFilter} 前置 423）；
     * 仅当同账号有 ≥2 个不同源 IP 同时处于锁定态时，才升级写账号级 DB 锁。
     */
    public void recordFailure(String username, String ip) {
        String user = canonicalUsername(username);
        String addr = normalizeIp(ip);
        Instant now = clock.instant();

        FailState state = buckets(user).compute(addr, (key, prev) -> advance(prev, now));

        boolean accountWide = state.lockedUntil() != null
                && lockedIpCount(user, now) >= ACCOUNT_LOCK_MIN_IPS;

        var update = Wrappers.<SysUserEntity>lambdaUpdate()
                .eq(SysUserEntity::getUsername, user)
                .set(SysUserEntity::getFailedAttempts, state.count());
        if (accountWide) {
            update.set(SysUserEntity::getLockedUntil, toLocal(state.lockedUntil()));
        }
        int updated = mapper.update(null, update);
        if (updated > 0) {
            knownUsers.add(user); // 真实账号：纳入受保护集合，淘汰时最后才考虑
        }
        evictIfOversized(now);

        if (accountWide) {
            if (updated > 0) {
                log.warn("账号级锁定：{} 个源 IP 连续失败达阈值：username={}, ip={}",
                        ACCOUNT_LOCK_MIN_IPS, user, addr);
            } else {
                log.warn("未知账号多源 IP 连续失败（内存锁定 15 分钟）：username={}, ip={}", user, addr);
            }
        } else if (state.lockedUntil() != null) {
            log.warn("单源 IP 连续失败达阈值（仅锁该 IP，不影响账号其他来源）：username={}, ip={}", user, addr);
        }
    }

    /** 登录成功：清当前 (账号+IP) 计数并复位 DB failed_attempts / 记录 last_login_at。 */
    public void recordSuccess(String username, String ip) {
        String user = canonicalUsername(username);
        String addr = normalizeIp(ip);
        failures.computeIfPresent(user, (key, byIp) -> {
            byIp.remove(addr);
            return byIp.isEmpty() ? null : byIp; // 空桶即摘除，避免留下永不使用的账号条目
        });
        mapper.update(null, Wrappers.<SysUserEntity>lambdaUpdate()
                .eq(SysUserEntity::getUsername, user)
                .set(SysUserEntity::getFailedAttempts, 0)
                .set(SysUserEntity::getLastLoginAt, LocalDateTime.now(clock)));
    }

    /** 内存锁剩余时长（未知账号路径 + 认证过滤器前置拦截）。 */
    public Optional<Duration> inMemoryRemaining(String username, String ip) {
        Map<String, FailState> byIp = failures.get(canonicalUsername(username));
        FailState state = byIp == null ? null : byIp.get(normalizeIp(ip));
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
        SysUserEntity entity = mapper.selectOne(Wrappers.<SysUserEntity>lambdaQuery()
                .eq(SysUserEntity::getUsername, canonicalUsername(username)));
        if (entity == null || entity.getLockedUntil() == null) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return entity.getLockedUntil().isAfter(now)
                ? Optional.of(Duration.between(now, entity.getLockedUntil()))
                : Optional.empty();
    }

    /** 内存计数表当前覆盖的账号数（观测/测试用，容量上限见 {@link #MAX_TRACKED_USERS}）。 */
    public int trackedUserCount() {
        return failures.size();
    }

    /** 仅供测试隔离使用：清空内存计数（每个用例独立起点，DB 状态由用例自行 DELETE 复位）。 */
    public void reset() {
        failures.clear();
        knownUsers.clear();
    }

    /**
     * 归一化账号名，对齐 {@code sys_user.username} 的 {@code utf8mb4_0900_ai_ci} 匹配语义
     * （大小写 + 重音不敏感）：不归一则大小写/重音变体各占一个计数桶，节流预算被按变体数放大。
     *
     * <p>NFKC（兼容形态，含全角）→ NFD 去组合符（去重音）→ 小写，是该排序规则在拉丁/ASCII 段的
     * 足够精确近似。合并两个**不同**真实账号在本系统不可能发生：uk_username 建在同一 ci/ai
     * 排序规则上，本就无法并存仅差大小写/重音的两个用户名。
     */
    static String canonicalUsername(String username) {
        if (username == null) {
            return "";
        }
        String compat = Normalizer.normalize(username.trim(), Normalizer.Form.NFKC);
        String withoutMarks = Normalizer.normalize(compat, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return withoutMarks.toLowerCase(Locale.ROOT);
    }

    private static String normalizeIp(String ip) {
        return ip == null ? "" : ip;
    }

    private ConcurrentHashMap<String, FailState> buckets(String user) {
        return failures.computeIfAbsent(user, key -> new ConcurrentHashMap<>());
    }

    /** 同账号当前处于锁定态的不同源 IP 数（升级判定的依据）。只扫本账号的桶。 */
    private long lockedIpCount(String user, Instant now) {
        Map<String, FailState> byIp = failures.get(user);
        if (byIp == null) {
            return 0;
        }
        return byIp.values().stream()
                .filter(state -> state.lockedUntil() != null && state.lockedUntil().isAfter(now))
                .count();
    }

    /**
     * 超限淘汰（登录端点免认证，无界 Map 即内存耗尽路径）。
     *
     * <p>两轮：先清「既未锁定、又已滑出窗口」的账号条目（正常访问产生的陈旧计数）；
     * 仍超限说明是洪泛撑起来的，此时清掉所有**非真实账号**条目——{@link #knownUsers} 规模由
     * sys_user 行数决定，故第二轮之后表必然回到上限之内，真实账号的锁在任何一轮都不会被误伤。
     */
    private void evictIfOversized(Instant now) {
        if (failures.size() <= MAX_TRACKED_USERS) {
            return;
        }
        failures.entrySet().removeIf(entry -> !knownUsers.contains(entry.getKey())
                && isIdle(entry.getValue(), now));
        if (failures.size() > MAX_TRACKED_USERS) {
            log.warn("登录失败计数表超限（{} 条 > {}），淘汰非真实账号条目——疑似撞库洪泛",
                    failures.size(), MAX_TRACKED_USERS);
            failures.entrySet().removeIf(entry -> !knownUsers.contains(entry.getKey()));
        }
    }

    /** 该账号所有源 IP 都既未锁定、也未被计数窗口覆盖 → 可安全淘汰。 */
    private static boolean isIdle(Map<String, FailState> byIp, Instant now) {
        return byIp.values().stream().noneMatch(state -> state.lockedUntil() != null
                ? state.lockedUntil().isAfter(now)
                : state.windowStart().plus(WINDOW).isAfter(now));
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

    private LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, clock.getZone());
    }
}
