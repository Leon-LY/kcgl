package com.kcgl.common.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 启动期弱值拒启（验收 12 四项安全自检第一项，docs/01 十二节「交付验收」）。
 *
 * <p>部署包里的 .env.example 是**样板**：必须被替换才能启动。此前 compose 的
 * {@code ${DB_PASSWORD:?...}} 只保证「变量有值」，把它照抄成 .env 一样能起来——
 * 于是 kcgl_dev_password 级别的口令进生产、且此后无人回头改。本类把「样板值/弱口令」
 * 从「约定」升级为**启动期硬失败**：宁可起不来，也不带弱值起。
 *
 * <p>判据（对 spring.datasource.password / spring.flyway.password / 访问源白名单）：
 * <ul>
 *   <li>长度 &lt; {@value #MIN_PASSWORD_LENGTH}（部署模板命令 {@code openssl rand -base64 24} 产出 32 字符）；</li>
 *   <li>整串命中弱值表（password / test / 123456 / kcgl_dev_* 等——本地开发默认值也在此列，
 *       正因如此本地开发栈必须显式关闭本守卫，见下）；</li>
 *   <li>含样板片段（change-me / changeme / your-password / example.com 等）；</li>
 *   <li>不同字符数 &lt; {@value #MIN_DISTINCT_CHARS}（aaaa… / abcabc… 这类「够长但无熵」）。</li>
 * </ul>
 *
 * <p><b>为什么默认开启、却在两个位置显式关闭</b>：本仓库的本地开发栈与 Testcontainers
 * 集成测试用的是**按设计的弱口令**（.env.example 首行即写明「仅本地开发用途的弱默认值」），
 * 它们不是部署面。所以关闭点只有两处、且都在沙箱侧：根 docker-compose.yml 的 app 服务
 * （{@code KCGL_SECRET_GUARD=false}）与 src/test/resources/application.properties。
 * 部署包（deploy/docker-compose.yml）不设该变量——沿用默认值 true，即部署面永远是开着的；
 * 任何人自写 compose 部署时也不会漏掉这道闸（默认安全，而非默认放行）。
 *
 * <p>实现选 {@link BeanFactoryPostProcessor} 而非 {@code @PostConstruct}：BFPP 在任何
 * 单例（含 Flyway 初始化器）实例化之前执行，弱值在**连数据库之前**就被拦下——报错直接指向
 * 口令本身，而不是被 Flyway 的方言/连接异常带偏。
 */
@Component
@ConditionalOnProperty(name = "kcgl.security.secret-guard.enabled", havingValue = "true", matchIfMissing = true)
public class SecretStrengthGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    /** 口令最小长度：过短即弱，不设上限（部署模板产 32 字符）。 */
    static final int MIN_PASSWORD_LENGTH = 16;

    /** 不同字符数下限：拦住「够长但无熵」的填充型口令。 */
    static final int MIN_DISTINCT_CHARS = 8;

    /**
     * 整串命中的弱值表（小写比较）。含本地开发/测试默认值——它们是**弱口令**这件事
     * 本身成立，只是所在环境按设计不受本守卫管辖（见类注释的关闭点说明）。
     */
    private static final List<String> WEAK_EXACT = List.of(
            "test", "password", "passwd", "admin", "root", "secret", "default",
            "123456", "12345678", "qwerty", "letmein", "mysql", "changeme", "change-me",
            "kcgl_dev_pass", "kcgl_dev_password", "kcgl_dev_root", "kcgl_e2e_pass");

    /** 样板片段（小写包含匹配）：.env.example 抄来没改的痕迹。 */
    private static final List<String> PLACEHOLDER_MARKERS = List.of(
            "change-me", "changeme", "change_me", "your-password", "yourpassword",
            "placeholder", "example.com", "todo");

    /** 待检项：属性名 → 用途说明（用于拼接拒启信息，运维据此定位 .env 的哪一行）。 */
    private static final String KEY_DATASOURCE_PASSWORD = "spring.datasource.password";
    private static final String KEY_FLYWAY_PASSWORD = "spring.flyway.password";
    private static final String KEY_ALLOWED_ORIGINS = "kcgl.security.allowed-origins";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        List<String> violations = check(environment);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("""
                    拒绝启动：检测到弱值/样板值（验收 12 弱值拒启）。请修正部署 .env 后重启：
                    %s
                    生成强随机口令：openssl rand -base64 24（三项密码均需替换 .env.example 的 change-me-*）
                    若确属本地沙箱环境，显式关闭本守卫：KCGL_SECRET_GUARD=false
                    """.formatted(String.join("\n", violations)));
        }
    }

    /**
     * 逐项校验并汇总违例（返回空列表=通过）。独立于上下文的纯函数：单测直接构造
     * Environment 断言判据，不必为了测一条规则启动整个应用。
     */
    public static List<String> check(Environment environment) {
        List<String> violations = new ArrayList<>();

        violations.addAll(checkPassword(KEY_DATASOURCE_PASSWORD,
                environment.getProperty(KEY_DATASOURCE_PASSWORD), true));
        // Flyway 口令是可选属性：部署栈由独立 migrate 容器执行迁移、app 侧关闭 Flyway
        // （docker-compose 的 SPRING_FLYWAY_ENABLED=false），故仅在配置了才校验。
        violations.addAll(checkPassword(KEY_FLYWAY_PASSWORD,
                environment.getProperty(KEY_FLYWAY_PASSWORD), false));

        String origins = environment.getProperty(KEY_ALLOWED_ORIGINS);
        if (origins != null) {
            String lower = origins.toLowerCase(Locale.ROOT);
            for (String marker : PLACEHOLDER_MARKERS) {
                if (lower.contains(marker)) {
                    violations.add("  - " + KEY_ALLOWED_ORIGINS + "：含样板片段「" + marker
                            + "」（实际访问源，如 https://kcgl.example.jp；填错=带 Origin 的写操作全 403）");
                }
            }
        }
        return violations;
    }

    private static List<String> checkPassword(String key, String value, boolean required) {
        if (value == null || value.isBlank()) {
            // 未配置同样拒启：compose 的 ${VAR:?} 只挡住「变量未设置」，挡不住空串；
            // 可选属性（Flyway 口令）留空是合法状态，仅 required 项报违例。
            return required ? List.of("  - " + key + "：未配置或为空") : List.of();
        }
        String lower = value.toLowerCase(Locale.ROOT);
        List<String> violations = new ArrayList<>();
        if (value.length() < MIN_PASSWORD_LENGTH) {
            violations.add("  - " + key + "：长度 " + value.length() + " < " + MIN_PASSWORD_LENGTH);
        }
        if (WEAK_EXACT.contains(lower)) {
            violations.add("  - " + key + "：命中弱值表（" + lower + "）");
        }
        for (String marker : PLACEHOLDER_MARKERS) {
            if (lower.contains(marker)) {
                violations.add("  - " + key + "：含样板片段「" + marker + "」（.env.example 原样未改？）");
            }
        }
        if (lower.chars().distinct().count() < MIN_DISTINCT_CHARS) {
            violations.add("  - " + key + "：不同字符数 < " + MIN_DISTINCT_CHARS + "（低熵填充，非随机口令）");
        }
        return violations;
    }
}
