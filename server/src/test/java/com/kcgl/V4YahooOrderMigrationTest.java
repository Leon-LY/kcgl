package com.kcgl;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V4（受注 xlsx 重校准）前向迁移测试（D-069；docs/01 十一节「V(n-1)+种子 → 跑 V(n) →
 * 行数不变/不变量成立」纪律）。
 *
 * 演练链：容器只跑到 V3（spring.flyway.target=3）→ 种入旧形状数据（uk_auction 单拍卖
 * 唯一时代的 listing 行+含 encoding_detected 的批次行）→ 手动推进 Flyway 到最新（V4）→ 断言：
 * - listing 行全数保全；order_id/note 新列就位；encoding_detected 退役；
 * - uk_auction → uk_item_auction：まとめ売り（同拍卖两商品两行）放行、
 *   (item,auction) 对重复仍拒绝、未匹配占位行（item_id NULL）不受索引保护
 *   （NULL 不参与唯一判定——幂等由导入单线程管线的代码级 UPSERT 承担）。
 */
@SpringBootTest(properties = "spring.flyway.target=3")
@Testcontainers
class V4YahooOrderMigrationTest {

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
    void v4_relaxesAuctionKey_toItemAuctionPair_addsOrderColumns() {
        seedLegacyData();

        // —— 推进到 V4（Spring 自动配置停在 V3；新实例默认 target=latest）
        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history "
                + "WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1", String.class))
                .isEqualTo("4");

        // —— 存量行保全（旧单拍卖唯一时代的两行：一行挂商品、一行未匹配占位）
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM yahoo_listing WHERE yahoo_auction_id IN ('auc-1','auc-2')",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT item_id FROM yahoo_listing WHERE yahoo_auction_id = 'auc-1'", Long.class))
                .isEqualTo(101L);
        assertThat(jdbc.queryForObject(
                "SELECT item_id FROM yahoo_listing WHERE yahoo_auction_id = 'auc-2'", Long.class))
                .isNull();

        // —— 新旧结构：order_id/note 就位、encoding_detected 退役
        assertThat(columnExists("yahoo_listing", "order_id")).isTrue();
        assertThat(columnExists("yahoo_import_batch", "note")).isTrue();
        assertThat(columnExists("yahoo_import_batch", "encoding_detected")).isFalse();

        // —— uk_item_auction 语义三面
        // ① まとめ売り：同拍卖两商品两行放行（D-069 5 本体）
        jdbc.update("INSERT INTO yahoo_listing (yahoo_auction_id, item_id, status) "
                + "VALUES ('auc-multi', 201, 2), ('auc-multi', 202, 2)");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM yahoo_listing WHERE yahoo_auction_id = 'auc-multi'",
                Integer.class)).isEqualTo(2);
        // ② (item,auction) 对重复仍拒绝（行级幂等键本体）
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO yahoo_listing (yahoo_auction_id, item_id, status) "
                        + "VALUES ('auc-multi', 201, 2)"))
                .isInstanceOf(DuplicateKeyException.class);
        // ③ 未匹配占位行（item_id NULL）不受索引保护——NULL 不参与唯一判定，
        //    两条同拍卖 NULL 行可共存（幂等由代码级 UPSERT 承担，docs/01 7.4）
        jdbc.update("INSERT INTO yahoo_listing (yahoo_auction_id, item_id, status) "
                + "VALUES ('auc-null', NULL, 2), ('auc-null', NULL, 2)");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM yahoo_listing WHERE yahoo_auction_id = 'auc-null'",
                Integer.class)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 种子（V3 旧形状）

    private void seedLegacyData() {
        jdbc.update("INSERT INTO sys_user (username, password_hash, display_name, role, enabled, must_change_pwd) "
                + "VALUES ('migr4', 'x', '移行確認', 1, 1, 0)");
        jdbc.update("INSERT INTO yahoo_listing (yahoo_auction_id, item_id, raw_item_code, item_code, "
                + "list_price, sold_price, status, listed_at, closed_at) "
                + "VALUES ('auc-1', 101, 'HT9-A1X', 'HT9-A1X', 3000, 12000, 2, '2026-09-10 10:00:00', '2026-09-15 21:00:00')");
        jdbc.update("INSERT INTO yahoo_listing (yahoo_auction_id, item_id, raw_item_code, "
                + "list_price, status) VALUES ('auc-2', NULL, 'M-A8-F9', 2000, 1)");
        jdbc.update("INSERT INTO yahoo_import_batch (file_sha256, original_filename, "
                + "encoding_detected, status, uploaded_by) "
                + "VALUES ('seed-sha-4-1', 'export.csv', 'MS932', 1, "
                + "(SELECT id FROM sys_user WHERE username = 'migr4'))");
    }

    private boolean columnExists(String table, String column) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column) > 0;
    }
}
