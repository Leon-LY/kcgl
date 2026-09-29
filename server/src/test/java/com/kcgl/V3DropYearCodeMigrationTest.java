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
 * V3（去年代号）前向迁移数据保全测试（D-068；docs/01 十一节「V(n-1)+种子 → 跑 V(n) →
 * 行数不变/不变量成立」纪律——V3 是首个破坏性迁移，纯加法断言不够，必须带数据演练）。
 *
 * 演练链：容器只跑到 V2（spring.flyway.target=2）→ 种入旧形状数据（item 含 year/year_code
 * 快照列、计数器含 year 桶键）→ 手动推进 Flyway 到最新（V3）→ 断言：
 * - 商品行全数保全（含作废件——号仍占 uk 空间）；
 * - year_code 表与 year 快照列退役；
 * - 计数器从快照列按位置序重建：跨年同月合并单桶（D-068 核心约束——否则次年重启 A1 撞
 *   uk_item_code）、前缀取进位序最大者（AA=27＞Z=26，纯字典序方向相反）、含作废件
 *   （B7 而非 B3——若重建漏掉作废件，后续生成号必撞已占用的 B7）。
 */
@SpringBootTest(properties = "spring.flyway.target=2")
@Testcontainers
class V3DropYearCodeMigrationTest {

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
    void v3_preservesItems_rebuildsCountersByPositionOrder_mergesCrossYearBuckets() {
        // —— 迁移前（V2 态）：旧形状在位
        assertThat(tableExists("year_code")).isTrue();
        seedLegacyData();

        // —— 推进到 V3（Spring 自动配置停在 V2；新实例默认 target=latest）
        Flyway.configure().dataSource(dataSource).load().migrate();

        // —— 商品行保全（7/7，作废件也在）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM item", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM item WHERE item_code = 'HTK9-B7X' AND voided = 1",
                Integer.class)).isEqualTo(1);
        // 存量号保持旧格式字面值（快照语义：号永不改写；新号走新格式不撞 uk）
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM item WHERE item_code IN ('HTK9-A1X','HTL9-A2X','ZZZ9-AA2X')",
                Integer.class)).isEqualTo(3);

        // —— 旧结构退役
        assertThat(jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = 1 "
                        + "ORDER BY installed_rank DESC LIMIT 1", String.class)).isEqualTo("3");
        assertThat(tableExists("year_code")).isFalse();
        assertThat(columnExists("item", "year")).isFalse();
        assertThat(columnExists("item", "year_code")).isFalse();
        assertThat(columnExists("seq_item_code", "year")).isFalse();

        // —— 计数器重建：HT 9 月桶 = 跨年（2026+2027）合并单桶 B7
        //    2026 件 A1/A5/B3 + 作废 B7 + 2027 件 A2 → 位置序最大前缀 B、其内最大流水 7
        Map<String, Object> htBucket = jdbc.queryForMap(
                "SELECT cur_prefix, cur_seq FROM seq_item_code WHERE venue_id = ? AND month = 9",
                venueIdByCode("HT"));
        assertThat(htBucket.get("cur_prefix")).isEqualTo("B");
        assertThat(htBucket.get("cur_seq")).isEqualTo(7);
        //    跨年合并硬断言：同会场同月全库仅此一桶（分年两桶=次年 A1 重启撞 uk）
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM seq_item_code WHERE venue_id = ? AND month = 9",
                Integer.class, venueIdByCode("HT"))).isEqualTo(1);

        // —— ZZ 9 月桶：Z9 与 AA2 并存 → AA2（进位序 AA＞Z；纯字典序会错选 Z9，D-058 D）
        Map<String, Object> zzBucket = jdbc.queryForMap(
                "SELECT cur_prefix, cur_seq FROM seq_item_code WHERE venue_id = ? AND month = 9",
                venueIdByCode("ZZ"));
        assertThat(zzBucket.get("cur_prefix")).isEqualTo("AA");
        assertThat(zzBucket.get("cur_seq")).isEqualTo(2);

        // 旧计数器行（A/0、Z/0）已删净重建：全库仅两桶
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM seq_item_code", Integer.class)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 种子（V2 旧形状）

    /** 旧格式号：{会场}{年代号}{月}-{前缀}{流水}{价格码}，2026=K/2027=L。 */
    private void seedLegacyData() {
        jdbc.update("INSERT INTO auction_venue (code, name) VALUES ('HT', '飛騨古民具市'), ('ZZ', 'テスト会場')");
        long ht = venueIdByCode("HT");
        long zz = venueIdByCode("ZZ");

        // HT 2026-09 桶：A1/A5/B3 + 作废 B7（作废件占 uk 空间，重建必须计入）
        seedLegacyItem("HTK9-A1X", ht, 2026, "K", "A", 1, 0);
        seedLegacyItem("HTK9-A5X", ht, 2026, "K", "A", 5, 0);
        seedLegacyItem("HTK9-B3X", ht, 2026, "K", "B", 3, 0);
        seedLegacyItem("HTK9-B7X", ht, 2026, "K", "B", 7, 1);
        // HT 2027-09 桶（跨年同月）：A2——V3 后与 2026-09 合并
        seedLegacyItem("HTL9-A2X", ht, 2027, "L", "A", 2, 0);
        // ZZ 2026-09 桶：Z9 与 AA2 并存（位置序裁决）
        seedLegacyItem("ZZZ9-Z9X", zz, 2026, "K", "Z", 9, 0);
        seedLegacyItem("ZZZ9-AA2X", zz, 2026, "K", "AA", 2, 0);

        // 旧形计数器（含 year 桶键）：V3 须删净重建而非保留（A/0 与重建值 B7 冲突即证）
        jdbc.update("INSERT INTO seq_item_code (venue_id, `year`, month, cur_prefix, cur_seq) "
                + "VALUES (?, 2026, 9, 'A', 0), (?, 2027, 9, 'A', 0)", ht, ht);
        jdbc.update("INSERT INTO seq_item_code (venue_id, `year`, month, cur_prefix, cur_seq) "
                + "VALUES (?, 2026, 9, 'Z', 0)", zz);
    }

    private void seedLegacyItem(String code, long venueId, int year, String yearCode,
            String prefix, int seq, int voided) {
        jdbc.update("""
                INSERT INTO item (item_code, venue_id, venue_code, `year`, year_code, buy_month,
                    seq_prefix, seq_no, buy_date, purchase_price, price_band_code, warehouse,
                    stock_status, sale_status, voided, deleted, created_by)
                VALUES (?, ?, ?, ?, ?, 9, ?, ?, '2026-09-15', 1000, 'X', 1, 0, 0, ?, 0, 1)
                """, code, venueId, code.substring(0, 2), year, yearCode, prefix, seq, voided);
    }

    private long venueIdByCode(String code) {
        return jdbc.queryForObject(
                "SELECT id FROM auction_venue WHERE code = ?", Long.class, code);
    }

    private boolean tableExists(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, table) > 0;
    }

    private boolean columnExists(String table, String column) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column) > 0;
    }
}
