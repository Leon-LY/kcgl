package com.kcgl.module.auth;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kcgl.module.user.SysUserEntity;
import com.kcgl.module.user.SysUserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LoginLockService 纯单元测试（不启容器）：覆盖计数语义中与 DB 无关的部分——
 * 账号名归一（F2）、计数表容量上限（F3）、窗口滑出与锁定到期时间线。
 * DB 交互路径（锁写入 sys_user、LockedException 响应）由 AuthIntegrationTest 覆盖。
 */
class LoginLockServiceTest {

    /** 测试里唯一"真实存在"的账号：mapper 仅对含此名的更新返回 1 行受影响。 */
    private static final String REAL_USER = "kept";
    private static final String ATTACKER_IP = "203.0.113.7";

    /** 可变时钟：直接推进时间验证窗口/锁定到期，不 sleep。 */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-07T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Tokyo");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }
    }

    private MutableClock clock;
    private LoginLockService service;

    /**
     * 手动喂一次 MyBatis-Plus 的实体元信息缓存：LambdaUpdateWrapper 生成列名要靠它，
     * 不启 Spring 上下文时无人初始化（否则报 "can not find lambda cache for this entity"）。
     */
    @BeforeAll
    static void initMybatisPlusMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUserEntity.class);
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        SysUserMapper mapper = mock(SysUserMapper.class);
        // 用"包装器参数里是否出现真实账号名"模拟 DB 的命中语义（真实 DB 中该列是 ci/ai 排序规则，
        // 变体都会归一成同一个名字，故这里只需比归一名）
        when(mapper.update(isNull(), any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> wrapper = invocation.getArgument(1);
            wrapper.getSqlSegment(); // 触发惰性拼装：参数值此刻才会落进 paramNameValuePairs
            Collection<Object> values = wrapper.getParamNameValuePairs().values();
            return values.contains(REAL_USER) ? 1 : 0;
        });
        service = new LoginLockService(mapper, clock);
    }

    // ------------------------------------------------------------------ F2 账号名归一

    @Test
    void canonicalUsername_matchesCaseAccentAndWidthInsensitiveLogin() {
        assertThat(LoginLockService.canonicalUsername(" Cafe ")).isEqualTo("cafe");
        assertThat(LoginLockService.canonicalUsername("CAFÉ")).isEqualTo("cafe");
        assertThat(LoginLockService.canonicalUsername("café")).isEqualTo("cafe");
        assertThat(LoginLockService.canonicalUsername("ＣＡＦＥ")).isEqualTo("cafe");
        assertThat(LoginLockService.canonicalUsername(null)).isEmpty();
        assertThat(LoginLockService.canonicalUsername("   ")).isEmpty();
    }

    /**
     * F2 回归：大小写/重音变体必须**共用同一个 (账号+IP) 桶**。
     * 不归一时每个变体各占一桶，5 次预算被按变体数放大，节流形同虚设。
     */
    @Test
    @Tag("regression")
    void recordFailure_caseAndAccentVariants_shareSingleBucket() {
        String[] variants = {"cafe", "CAFE", "café", "CAFÉ", "Cafe"};

        for (String variant : variants) {
            service.recordFailure(variant, ATTACKER_IP);
        }

        assertThat(service.inMemoryRemaining("café", ATTACKER_IP))
                .as("5 个变体共用一桶，累计达阈值即锁定")
                .isPresent();
        assertThat(service.inMemoryRemaining("cafe", "203.0.113.99"))
                .as("换源 IP 是新桶，不受影响")
                .isEmpty();
    }

    // ------------------------------------------------------------------ F3 计数表容量上限

    /**
     * F3 回归：登录端点免认证，计数表若无上限即是一条内存耗尽路径（并令升级判定退化为全表扫描）。
     * 洪泛未知账号后表必须有界，且**真实账号的锁不得被挤掉**。
     */
    @Test
    @Tag("regression")
    void recordFailure_unknownUserFlood_staysBoundedAndKeepsRealAccountLock() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure(REAL_USER, ATTACKER_IP);
        }
        assertThat(service.inMemoryRemaining(REAL_USER, ATTACKER_IP)).isPresent();

        int flood = LoginLockService.MAX_TRACKED_USERS + 512;
        for (int i = 0; i < flood; i++) {
            service.recordFailure("ghost-" + i, ATTACKER_IP);
        }

        assertThat(service.trackedUserCount())
                .as("洪泛 %d 个未知账号后计数表仍须有界", flood)
                .isLessThanOrEqualTo(LoginLockService.MAX_TRACKED_USERS);
        assertThat(service.inMemoryRemaining(REAL_USER, ATTACKER_IP))
                .as("真实账号的内存锁不得被未知账号洪泛淘汰")
                .isPresent();
    }

    // ------------------------------------------------------------------ 时间线

    @Test
    void recordFailure_windowElapsed_restartsCount() {
        for (int i = 0; i < LoginLockService.MAX_ATTEMPTS - 1; i++) {
            service.recordFailure(REAL_USER, ATTACKER_IP);
        }

        clock.advance(LoginLockService.WINDOW.plusSeconds(1));
        service.recordFailure(REAL_USER, ATTACKER_IP);

        assertThat(service.inMemoryRemaining(REAL_USER, ATTACKER_IP))
                .as("窗口滑出后重新计数，不应累计锁定")
                .isEmpty();
    }

    @Test
    void inMemoryRemaining_reportsCountdownAndExpiresWithLock() {
        for (int i = 0; i < LoginLockService.MAX_ATTEMPTS; i++) {
            service.recordFailure(REAL_USER, ATTACKER_IP);
        }
        assertThat(service.inMemoryRemaining(REAL_USER, ATTACKER_IP))
                .hasValueSatisfying(remaining -> assertThat(remaining)
                        .isEqualTo(LoginLockService.LOCK_DURATION));

        clock.advance(LoginLockService.LOCK_DURATION.plusSeconds(1));

        assertThat(service.inMemoryRemaining(REAL_USER, ATTACKER_IP)).isEmpty();
    }

    @Test
    void recordSuccess_clearsBucketForThatIpOnly() {
        service.recordFailure(REAL_USER, ATTACKER_IP);
        service.recordFailure(REAL_USER, "203.0.113.8");

        service.recordSuccess(REAL_USER, ATTACKER_IP);

        assertThat(service.trackedUserCount()).as("桶内仍有另一 IP 的计数").isEqualTo(1);
    }
}
