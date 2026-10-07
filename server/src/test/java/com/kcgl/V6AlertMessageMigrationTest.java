package com.kcgl;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V6（系统告警提示语结构化，D-128）前向迁移测试（docs/01 十一节「V(n-1)+种子 →
 * 跑 V(n) → 行数不变/不变量成立」纪律）。
 *
 * <p>演练链：容器只跑到 V5（spring.flyway.target=5）→ 种入 V5 形状的告警行（message
 * 为日文句子、无结构化列）→ 手动推进 Flyway 到最新（V6）→ 断言：
 * <ul>
 *   <li>message_key/message_params 就位，且 message 仍是 NOT NULL（旧行兜底、server log
 *       与诊断包导出都按语言无关的日文原文消费，不能因加了键就放松约束）；</li>
 *   <li>存量告警全数保全：message/payload/status 原样、新列 NULL——这正是「历史行回退
 *       原文」契约的数据库侧证据，前端只在 messageKey 有值时才走 i18n；</li>
 *   <li>uk_dedup 去重键不受迁移影响（存量行仍受唯一约束保护）。</li>
 * </ul>
 */
@SpringBootTest(properties = "spring.flyway.target=5")
@Testcontainers
class V6AlertMessageMigrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            // 与 V1SchemaMigrationTest 同参数（时区为数字偏移无需加载时区表）
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;

    @Test
    void v6_addsStructuredMessageColumns_legacyAlertsKeepPlainText() {
        seedLegacyAlert();

        // —— 推进到 V6（Spring 自动配置停在 V5；新实例默认 target=latest）
        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version = '6' AND success = 1", Integer.class)).isEqualTo(1);

        // —— 新列就位
        assertThat(columnExists("sys_alert", "message_key")).isTrue();
        assertThat(columnExists("sys_alert", "message_params")).isTrue();
        // message 保持 NOT NULL：键是并行新增，不是替换
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'sys_alert' "
                + "AND column_name = 'message'", String.class)).isEqualTo("NO");

        // —— 存量行保全：日文原文原样，结构化列空（前端回退原文而非渲染空串）
        Map<String, Object> legacy = jdbc.queryForMap(
                "SELECT message, message_key, message_params, payload, status FROM sys_alert "
                        + "WHERE dedup_key = 'seed-alert-6-1'");
        assertThat(legacy.get("message"))
                .isEqualTo("ディスク使用率が92%に達しました（閾値80%）");
        assertThat(legacy.get("message_key")).isNull();
        assertThat(legacy.get("message_params")).isNull();
        assertThat(((String) legacy.get("payload")).replace(" ", "")).contains("\"pct\":92");
        assertThat(((Number) legacy.get("status")).intValue()).isZero();

        // 行数守恒（迁移不删行），去重键仍有效
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_alert", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'sys_alert' "
                + "AND index_name = 'uk_dedup'", Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 种子（V5 旧形状）

    private void seedLegacyAlert() {
        jdbc.update("INSERT INTO sys_alert (type, dedup_key, level, message, payload, status) "
                + "VALUES ('DISK_USAGE', 'seed-alert-6-1', 3, ?, '{\"pct\":92}', 0)",
                "ディスク使用率が92%に達しました（閾値80%）");
    }

    private boolean columnExists(String table, String column) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column) > 0;
    }
}
