package com.kcgl.common.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kcgl.module.user.SysUserEntity;
import com.kcgl.module.user.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * E2E 测试数据种子：仅 e2e profile 激活（SPRING_PROFILES_ACTIVE=e2e，生产/本地开发永不加载），
 * 创建三角色固定账号 + 首登改密账号。固定弱密码仅存在于测试栈——E2E 的 MySQL 为一次性容器，
 * 每次运行全新建库，不存在脏数据残留。
 * Order(0)：先于 BootstrapAdminRunner 执行（其"空库才引导"逻辑看到种子账号即静默跳过，
 * 避免 admin 拿到随机密码导致 E2E 无法登录）。
 */
@Component
@Profile("e2e")
@Order(0)
public class E2eDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(E2eDataSeeder.class);

    static final String E2E_PASSWORD = "e2e-pass-123456";

    private final SysUserMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public E2eDataSeeder(SysUserMapper mapper, PasswordEncoder passwordEncoder, Clock clock) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        upsert("admin", "管理者 太郎", 1, false);
        upsert("editor", "編集者 花子", 2, false);
        upsert("viewer", "閲覧者 次郎", 3, false);
        upsert("taro", "田中太郎", 2, true);
        log.info("E2E 种子就绪：admin/editor/viewer（三角色）+ taro（首登强制改密）");
    }

    private void upsert(String username, String displayName, int role, boolean mustChangePwd) {
        if (mapper.selectCount(new LambdaQueryWrapper<SysUserEntity>()
                .eq(SysUserEntity::getUsername, username)) > 0) {
            return;
        }
        SysUserEntity user = new SysUserEntity();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(E2E_PASSWORD));
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setLocale("ja-JP");
        user.setEnabled(1);
        user.setMustChangePwd(mustChangePwd ? 1 : 0);
        user.setFailedAttempts(0);
        user.setCreatedAt(LocalDateTime.now(clock));
        user.setUpdatedAt(LocalDateTime.now(clock));
        mapper.insert(user);
    }
}
