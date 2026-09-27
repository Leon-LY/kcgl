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
 * V1__init.sql 迁移契约测试（docs/01 十一节：schema 是一切业务代码的契约）。
 * 后续新增 V(n) 迁移时，在此类扩展「V(n-1)+种子 → 跑 V(n) → 行数不变/不变量成立」的前向兼容用例。
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
                "sys_user", "auction_venue", "year_code", "price_band", "item",
                "seq_item_code", "item_image", "stock_ledger", "yahoo_listing",
                "yahoo_import_batch", "stocktake", "stocktake_scan", "stocktake_diff",
                "operation_log", "sys_setting", "sys_alert", "client_error");
    }

    @Test
    void yearCodeSeed_from2016A_annualIncrementSkippingIO() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM year_code", Integer.class);
        assertThat(count).isEqualTo(26);
        assertThat(jdbc.queryForObject("SELECT code FROM year_code WHERE `year` = 2016", String.class))
                .isEqualTo("A");
        // 需求示例锚点：2026=K、2027=L（docs/01 D-003）
        assertThat(jdbc.queryForObject("SELECT code FROM year_code WHERE `year` = 2026", String.class))
                .isEqualTo("K");
        assertThat(jdbc.queryForObject("SELECT code FROM year_code WHERE `year` = 2027", String.class))
                .isEqualTo("L");
        assertThat(jdbc.queryForObject("SELECT code FROM year_code WHERE `year` = 2041", String.class))
                .isEqualTo("Z");
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
                "INSERT INTO seq_item_code (venue_id, `year`, month, cur_prefix, cur_seq) VALUES (1, 2026, 13, 'A', 0)"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_seq_month");
    }

    @Test
    void generatedColumn_totalCostDerivedFromFees() {
        jdbc.update("INSERT INTO auction_venue (code, name) VALUES ('HT', 'テスト会場')");
        Long venueId = jdbc.queryForObject("SELECT id FROM auction_venue WHERE code = 'HT'", Long.class);
        jdbc.update("""
                INSERT INTO item (item_code, venue_id, venue_code, `year`, year_code, buy_month,
                    seq_prefix, seq_no, buy_date, purchase_price, fee, shipping_fee, tax,
                    price_band_code, warehouse, created_by)
                VALUES ('HTK9-A1X', ?, 'HT', 2026, 'K', 9, 'A', 1, '2026-09-27', 1000, 200, 300, 100, 'X', 1, 1)
                """, venueId);
        Integer totalCost = jdbc.queryForObject(
                "SELECT total_cost FROM item WHERE item_code = 'HTK9-A1X'", Integer.class);
        assertThat(totalCost).isEqualTo(1600);
        // sold_price 未填 → profit 为 NULL（自然传播）
        Integer profit = jdbc.queryForObject(
                "SELECT profit FROM item WHERE item_code = 'HTK9-A1X'", Integer.class);
        assertThat(profit).isNull();
    }
}
