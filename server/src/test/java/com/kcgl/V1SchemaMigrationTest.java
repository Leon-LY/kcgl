package com.kcgl;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 迁移链契约测试（docs/01 十一节：schema 是一切业务代码的契约）——本类断言
 * V1→V2→V3 完整链跑完后的最终态。V3 为破坏性迁移（去年代号，D-068），其
 * 「V2+种子 → 跑 V3 → 行不变/计数器重建」前向数据保全用例见 {@link V3DropYearCodeMigrationTest}。
 * 后续新增 V(n) 迁移时，在此类扩展「V(n-1)+种子 → 跑 V(n) → 行数不变/不变量成立」用例。
 */
@SpringBootTest
@Testcontainers
class V1SchemaMigrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            // 注意：mysqld 无 --time-zone 参数（会以未知参数 exit 1），
            // 服务器默认时区参数为 --default-time-zone，数字偏移无需加载时区表
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migration_createsAll17Tables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history' "
                        + "ORDER BY table_name", String.class);
        assertThat(tables).containsExactlyInAnyOrder(
                "sys_user", "auction_venue", "price_band", "item",
                "seq_item_code", "item_image", "stock_ledger", "yahoo_listing",
                "yahoo_import_batch", "stocktake", "stocktake_scan", "stocktake_diff",
                "operation_log", "sys_setting", "sys_alert", "client_error",
                "excel_import_batch");
    }

    @Test
    void sysSettingSeed_contains5Entries() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_setting", Integer.class);
        assertThat(count).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT `value` FROM sys_setting WHERE `key` = 'slow_move.warn_days'", String.class))
                .isEqualTo("30");
    }

    @Test
    void checkConstraint_stocktakeInvalidWarehouse_rejected() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO stocktake (stocktake_no, warehouse, created_by) VALUES ('PDX', 3, 1)"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_st_wh");
    }

    @Test
    void checkConstraint_counterInvalidMonth_rejected() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO seq_item_code (venue_id, month, cur_prefix, cur_seq) VALUES (1, 13, 'A', 0)"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_seq_month");
    }

    @Test
    void generatedColumn_totalCostDerivedFromFees() {
        jdbc.update("INSERT INTO auction_venue (code, name) VALUES ('HT', 'テスト会場')");
        Long venueId = jdbc.queryForObject("SELECT id FROM auction_venue WHERE code = 'HT'", Long.class);
        jdbc.update("""
                INSERT INTO item (item_code, venue_id, venue_code, buy_month,
                    seq_prefix, seq_no, buy_date, purchase_price, fee, shipping_fee, tax,
                    price_band_code, warehouse, created_by)
                VALUES ('HT9-A1X', ?, 'HT', 9, 'A', 1, '2026-09-27', 1000, 200, 300, 100, 'X', 1, 1)
                """, venueId);
        Integer totalCost = jdbc.queryForObject(
                "SELECT total_cost FROM item WHERE item_code = 'HT9-A1X'", Integer.class);
        assertThat(totalCost).isEqualTo(1600);
        // sold_price 未填 → profit 为 NULL（自然传播）
        Integer profit = jdbc.queryForObject(
                "SELECT profit FROM item WHERE item_code = 'HT9-A1X'", Integer.class);
        assertThat(profit).isNull();
    }

    /**
     * V2 前向迁移伴随锚点（D-058 J）：V2 为纯加法（新建 excel_import_batch，不动既有表），
     * 既有种子数据经完整迁移链后不变——sysSettingSeed 用例承担「行不变」断言；此处钉死新表形状与约束。
     */
    @Test
    void v2ExcelImportBatch_constraintsEnforced() {
        String sha = "a".repeat(64);
        jdbc.update("""
                INSERT INTO excel_import_batch(file_sha256, original_filename, uploaded_by)
                VALUES (?, 'items.xlsx', 1)
                """, sha);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM excel_import_batch WHERE original_filename = 'items.xlsx'",
                Integer.class)).isZero();
        // 状态值域 CHECK
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE excel_import_batch SET status = 3 WHERE original_filename = 'items.xlsx'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_excel_batch_status");
        // 同 sha 重复上传幂等键
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO excel_import_batch(file_sha256, original_filename, uploaded_by)
                VALUES (?, 'items-again.xlsx', 1)
                """, sha))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_sha");
    }
}
