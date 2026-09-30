package com.kcgl;

import com.kcgl.common.config.SecretStrengthGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动期弱值拒启（D-085，验收 12 四项安全自检第一项）。
 *
 * <p>两段：{@code check_} 系列锁判据本身（哪类值算弱、哪类放行），
 * {@code context_} 系列用 ApplicationContextRunner 真起一次上下文——
 * 因为守卫的实现是 BeanFactoryPostProcessor，**只有真跑一遍 refresh 才能证明
 * 「弱值确实让上下文起不来」**（单测判据全绿 + 守卫没挂上，是最典型的假绿）。
 */
class SecretStrengthGuardTest {

    private static final String STRONG = "Kq7+vU2xLmB9zR4tWp1yNc6dHs0aE3gJ";   // openssl rand -base64 24 形态
    private static final String ORIGINS = "https://kcgl.leon-ly.jp";

    private static Environment environmentWith(String key, String value) {
        return new MockEnvironment().withProperty(key, value);
    }

    @ParameterizedTest
    @CsvSource({
            // 样板值：.env.example 照抄（长度也常不达标，两条违例都该报）
            "change-me-root",
            "change-me-app",
            "change-me-migrate",
            // 弱值表整串命中
            "password",
            "kcgl_dev_password",
            "kcgl_e2e_pass",
            "123456",
            // 够长但无熵
            "aaaaaaaaaaaaaaaaaaaa",
            "abcabcabcabcabcabcab",
            // 短口令（下限 16 的边界值见 check_passwordExactlyAtMinLength_passes）
            "Str0ng!pw",
    })
    void check_weakDataSourcePassword_reportsViolation(String weak) {
        List<String> violations = SecretStrengthGuard.check(environmentWith("spring.datasource.password", weak));
        assertThat(violations).as("弱值 %s 必须被拒", weak).isNotEmpty();
    }

    @Test
    void check_strongDataSourcePassword_passes() {
        assertThat(SecretStrengthGuard.check(environmentWith("spring.datasource.password", STRONG))).isEmpty();
    }

    @Test
    void check_passwordExactlyAtMinLength_passes() {
        assertThat(SecretStrengthGuard.check(environmentWith("spring.datasource.password", "Str0ng!but-short")))
                .as("下限是「<16 即弱」：恰好 16 字符且非弱值表命中者放行（边界钉死，防日后收紧时误伤）")
                .isEmpty();
    }

    @Test
    void check_missingDataSourcePassword_reportsViolation() {
        assertThat(SecretStrengthGuard.check(new MockEnvironment()))
                .as("部署面口令缺失=拒启（compose 的 ${VAR:?} 挡不住空串）")
                .isNotEmpty();
    }

    @Test
    void check_absentFlywayPassword_passes() {
        Environment environment = new MockEnvironment().withProperty("spring.datasource.password", STRONG);
        assertThat(SecretStrengthGuard.check(environment))
                .as("Flyway 口令可选：部署栈由 migrate 容器执行迁移、app 侧关闭 Flyway")
                .isEmpty();
    }

    @Test
    void check_weakFlywayPassword_reportsViolation() {
        Environment environment = new MockEnvironment()
                .withProperty("spring.datasource.password", STRONG)
                .withProperty("spring.flyway.password", "change-me-migrate");
        assertThat(SecretStrengthGuard.check(environment)).isNotEmpty();
    }

    @Test
    void check_placeholderAllowedOrigins_reportsViolation() {
        Environment environment = new MockEnvironment()
                .withProperty("spring.datasource.password", STRONG)
                .withProperty("kcgl.security.allowed-origins", "http://change-me:8082");
        assertThat(SecretStrengthGuard.check(environment))
                .as("允许源留样板值=写操作全 403，属同一类「抄了没改」")
                .isNotEmpty();
    }

    @Test
    void check_strongSecretsAndRealOrigins_passes() {
        Environment environment = new MockEnvironment()
                .withProperty("spring.datasource.password", STRONG)
                .withProperty("kcgl.security.allowed-origins", ORIGINS);
        assertThat(SecretStrengthGuard.check(environment)).isEmpty();
    }

    @Test
    void context_weakPassword_failsToStart() {
        new ApplicationContextRunner()
                .withUserConfiguration(SecretStrengthGuard.class)
                .withPropertyValues("spring.datasource.password=change-me-app")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .as("拒启信息须指向被拒的键，运维据此定位 .env 行")
                            .hasMessageContaining("弱值拒启")
                            .hasMessageContaining("spring.datasource.password");
                });
    }

    @Test
    void context_strongPassword_startsCleanly() {
        new ApplicationContextRunner()
                .withUserConfiguration(SecretStrengthGuard.class)
                .withPropertyValues("spring.datasource.password=" + STRONG,
                        "kcgl.security.allowed-origins=" + ORIGINS)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void context_guardDisabled_startsEvenWithWeakPassword() {
        new ApplicationContextRunner()
                .withUserConfiguration(SecretStrengthGuard.class)
                .withPropertyValues("kcgl.security.secret-guard.enabled=false",
                        "spring.datasource.password=kcgl_dev_pass")
                .run(context -> assertThat(context)
                        .as("沙箱关闭点（根 compose / 测试 properties）必须真的放行")
                        .hasNotFailed());
    }
}
