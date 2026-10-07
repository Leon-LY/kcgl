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
 * V5（导入报告提示语结构化，D-127）前向迁移测试（docs/01 十一节「V(n-1)+种子 →
 * 跑 V(n) → 行数不变/不变量成立」纪律）。
 *
 * 演练链：容器只跑到 V4（spring.flyway.target=4）→ 种入 V4 形状的批次行（note 为
 * 日文補注、无结构化列）→ 手动推进 Flyway 到最新（V5）→ 断言：
 * - 新列就位（yahoo 三列、excel 两列），且 excel 不设 note_json（其 note 是
 *   计数器跳变说明「HT-9 A5→A12」，纯数据无日文短语，不预留空列）；
 * - 存量批次行全数保全：note/error_message 原样、新列 NULL——这正是「旧数据行
 *   回退日文兜底」契约的数据库侧证据，前端只在 code 有值时才走 i18n。
 */
@SpringBootTest(properties = "spring.flyway.target=4")
@Testcontainers
class V5ImportMessageMigrationTest {

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
    void v5_addsStructuredMessageColumns_legacyRowsKeepPlainText() {
        seedLegacyBatches();

        // —— 推进到 V5（Spring 自动配置停在 V4；新实例默认 target=latest）
        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version = '5' AND success = 1", Integer.class)).isEqualTo(1);

        // —— 新列就位
        assertThat(columnExists("yahoo_import_batch", "note_json")).isTrue();
        assertThat(columnExists("yahoo_import_batch", "error_message_code")).isTrue();
        assertThat(columnExists("yahoo_import_batch", "error_message_params")).isTrue();
        assertThat(columnExists("excel_import_batch", "error_message_code")).isTrue();
        assertThat(columnExists("excel_import_batch", "error_message_params")).isTrue();
        // 刻意的不对称：excel 的 note 无需结构化，不设空列
        assertThat(columnExists("excel_import_batch", "note_json")).isFalse();

        // —— 存量行保全：日文原文原样，结构化列空（前端回退原文而非渲染空串）
        Map<String, Object> yahoo = jdbc.queryForMap(
                "SELECT note, note_json, error_message, error_message_code FROM yahoo_import_batch "
                        + "WHERE file_sha256 = 'seed-sha-5-1'");
        assertThat(yahoo.get("note"))
                .isEqualTo("注文10004900（HT9-A1X・HT9-A2X）は複数商品のため単価が未分割です");
        assertThat(yahoo.get("note_json")).isNull();
        assertThat(yahoo.get("error_message_code")).isNull();

        assertThat(jdbc.queryForObject(
                "SELECT error_message FROM excel_import_batch WHERE file_sha256 = 'seed-sha-5-2'",
                String.class))
                .isEqualTo("最初のワークシートが空です");
        assertThat(jdbc.queryForObject(
                "SELECT error_message_code FROM excel_import_batch WHERE file_sha256 = 'seed-sha-5-2'",
                String.class)).isNull();

        // 行数守恒（迁移不删行）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM yahoo_import_batch", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM excel_import_batch", Integer.class))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ 种子（V4 旧形状）

    private void seedLegacyBatches() {
        jdbc.update("INSERT INTO sys_user (username, password_hash, display_name, role, enabled, must_change_pwd) "
                + "VALUES ('migr5', 'x', '移行確認', 1, 1, 0)");
        jdbc.update("INSERT INTO yahoo_import_batch (file_sha256, original_filename, status, "
                + "note, uploaded_by) VALUES ('seed-sha-5-1', 'orders.xlsx', 1, ?, "
                + "(SELECT id FROM sys_user WHERE username = 'migr5'))",
                "注文10004900（HT9-A1X・HT9-A2X）は複数商品のため単価が未分割です");
        jdbc.update("INSERT INTO excel_import_batch (file_sha256, original_filename, status, "
                + "error_message, uploaded_by) VALUES ('seed-sha-5-2', 'items.xlsx', 2, ?, "
                + "(SELECT id FROM sys_user WHERE username = 'migr5'))",
                "最初のワークシートが空です");
    }

    private boolean columnExists(String table, String column) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column) > 0;
    }
}
