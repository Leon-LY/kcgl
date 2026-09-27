package com.kcgl.common.config;

import com.kcgl.module.user.SysUserEntity;
import com.kcgl.module.user.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 空库引导：sys_user 为空时创建初始管理员 admin（随机一次性密码，仅启动日志打印一次，
 * must_change_pwd=1 强制首登改密）。随机哈希不进迁移脚本——V1 里的固定哈希一旦泄露
 * 等于所有部署共享同一密码。已有任意账号则静默跳过（幂等）。
 */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    /** 去掉易混淆字符（0/O/1/l/I）的无歧义字母表 */
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final int PASSWORD_LENGTH = 16;

    private final SysUserMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public BootstrapAdminRunner(SysUserMapper mapper, PasswordEncoder passwordEncoder, Clock clock) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (mapper.selectCount(null) > 0) {
            return;
        }
        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            password.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        SysUserEntity admin = new SysUserEntity();
        admin.setUsername("admin");
        admin.setPasswordHash(passwordEncoder.encode(password.toString()));
        admin.setDisplayName("管理者");
        admin.setRole(1);
        admin.setLocale("ja-JP");
        admin.setEnabled(1);
        admin.setMustChangePwd(1);
        admin.setFailedAttempts(0);
        admin.setCreatedAt(LocalDateTime.now(clock));
        admin.setUpdatedAt(LocalDateTime.now(clock));
        mapper.insert(admin);
        log.info("空库引导：已创建初始管理员 admin，一次性密码={}（仅本次启动打印，请立即登录并修改）",
                password);
    }
}
