package com.kcgl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 大盘/两仓统计集成测试（M5-③）：固定阈值种子（warn=10/alarm=20）下的
 * 条件聚合口径——在库/在途/已出库、本月入库（warehouse_in_date 业务日期）/
 * 本月出库（SELL/SCRAP/退回拍卖场 且 wh_from 非空）、滞销黄/红、在库货值
 * ∑total_cost、件均库龄；雅虎三活计数与最近成功批次新鲜度。
 * 种子日期取相对「今天」偏移：越月边界（today−3 等）的月归属随运行日漂移
 * → 期望值在测试内按 monthStart 现算，不硬编码。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class StatsIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String PASSWORD = "Sts-1234-t";
    static final LocalDate BUY_DATE = LocalDate.of(2026, 5, 10);

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    Clock clock;

    long bossId;
    long htVenueId;

    @BeforeEach
    void resetFixtures() {
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM stock_ledger");
        jdbcTemplate.update("DELETE FROM item");
        jdbcTemplate.update("DELETE FROM seq_item_code");
        jdbcTemplate.update("DELETE FROM sys_setting");
        jdbcTemplate.update("DELETE FROM yahoo_import_batch");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN ('boss','eichi','miru')");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('boss', ?, '管理者', 1, 1, 0), ('eichi', ?, '編集者', 2, 1, 0), ('miru', ?, '閲覧者', 3, 1, 0)
                """, ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD));
        bossId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = 'boss'", Long.class);
        jdbcTemplate.update("DELETE FROM auction_venue");
        jdbcTemplate.update("DELETE FROM price_band");
        jdbcTemplate.update(
                "INSERT INTO price_band(code, lower_bound, upper_bound, enabled) VALUES ('X', 0, 3000, 1)");
        jdbcTemplate.update(
                "INSERT INTO auction_venue(code, name, enabled) VALUES ('HT', '飛騨古民具市', 1)");
        htVenueId = jdbcTemplate.queryForObject("SELECT id FROM auction_venue WHERE code = 'HT'", Long.class);
        // 固定滞销阈值（黄 10 / 红 20）——慢漂移防御：不依赖默认 30/90
        jdbcTemplate.update(
                "INSERT INTO sys_setting(`key`, `value`) VALUES ('slow_move.warn_days','10'),('slow_move.alarm_days','20')");
    }

    private MockHttpSession loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .param("username", username).param("password", PASSWORD))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("登录应成功: %s",
                result.getResponse().getContentAsString()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession();
    }

    /** 直插商品行：精确控制仓/库存/销售/入库日/费用（统计谓词的被测对象；终态直插，不走状态机）。 */
    private long insertItem(String code, int warehouse, int stockStatus, int saleStatus,
            LocalDate inDate, long price, Integer fee, int voided, int deleted) {
        jdbcTemplate.update("""
                INSERT INTO item(item_code, venue_id, venue_code, buy_month,
                  seq_prefix, seq_no, buy_date, purchase_price, fee, price_band_code, warehouse,
                  stock_status, sale_status, voided, deleted, created_by, warehouse_in_date)
                VALUES (?, ?, 'HT', ?, 'A', 1, ?, ?, ?, 'X', ?, ?, ?, ?, ?, ?, ?)
                """,
                code, htVenueId, BUY_DATE.getMonthValue(), BUY_DATE, price, fee, warehouse,
                stockStatus, saleStatus, voided, deleted, bossId, inDate);
        return jdbcTemplate.queryForObject("SELECT id FROM item WHERE item_code = ?", Long.class, code);
    }

    private long insertItem(String code, int warehouse, int stockStatus, int saleStatus,
            LocalDate inDate, long price, Integer fee) {
        return insertItem(code, warehouse, stockStatus, saleStatus, inDate, price, fee, 0, 0);
    }

    /** 直插流水行：出库聚合只按 (txn_type, wh_from, created_at) 计数，商品终态另行直插。 */
    private void insertLedger(String reqId, int txnType, String itemCode, Integer whFrom, Integer whTo,
            int qtyChange, Integer returnDirection, LocalDateTime createdAt) {
        long itemId = jdbcTemplate.queryForObject(
                "SELECT id FROM item WHERE item_code = ?", Long.class, itemCode);
        jdbcTemplate.update("""
                INSERT INTO stock_ledger(client_req_id, txn_type, item_id, item_code,
                  wh_from, wh_to, qty_change, return_direction, operator_id, operator_name, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'boss', ?)
                """,
                reqId, txnType, itemId, itemCode, whFrom, whTo, qtyChange, returnDirection,
                bossId, createdAt);
    }

    private void insertBatch(char shaChar, String filename, int status, LocalDateTime finishedAt) {
        jdbcTemplate.update("""
                INSERT INTO yahoo_import_batch(file_sha256, original_filename, status, uploaded_by, created_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                String.valueOf(shaChar).repeat(64), filename, status, bossId,
                finishedAt.minusMinutes(5), finishedAt);
    }

    /** 种子全量：8 活件 + 作废/软删各一 + 7 流水行 + 3 批次（详见各测试的期望值推导）。 */
    private void seedAll(LocalDate today) {
        insertItem("ST-T1", 1, 0, 0, null, 1000, null);                       // 在途
        insertItem("ST-S1", 1, 1, 0, today, 1000, 500);                       // 在库 龄0 货值1500
        insertItem("ST-S2", 2, 1, 0, today.minusDays(40), 2000, null);        // 在库 龄40 → 红
        insertItem("ST-S3", 2, 1, 0, today.minusDays(15), 3000, null);        // 在库 龄15 → 黄
        insertItem("ST-S4", 1, 1, 2, today.minusDays(3), 4000, null);         // 在库 成交 → 雅虎待出荷
        insertItem("ST-S5", 1, 1, 3, today.minusDays(3), 1000, null);         // 在库 取消 → 雅虎未重上
        insertItem("ST-P1", 2, 2, 2, today.minusDays(2), 500, null);          // 已出库（不计货值/库龄）
        insertItem("ST-P2", 2, 2, 1, today.minusDays(50), 600, null);         // 已出库 仍在售 → 撤架待办
        insertItem("ST-V1", 1, 1, 0, today.minusDays(5), 700, null, 1, 0);    // 作废 → 全口径排除
        insertItem("ST-D1", 1, 1, 0, today.minusDays(5), 800, null, 0, 1);    // 软删 → 全口径排除

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate monthStart = today.withDayOfMonth(1);
        insertLedger("st-ledger-1", 3, "ST-P1", 2, null, -1, null, now);          // SELL wh2 本月 → 计
        insertLedger("st-ledger-2", 4, "ST-S5", 1, null, -1, null, now);          // SCRAP wh1 本月 → 计
        insertLedger("st-ledger-3", 6, "ST-S4", 1, null, -1, 2, now);             // 退回拍卖场 wh1 → 计
        insertLedger("st-ledger-4", 6, "ST-S2", null, 1, 1, 1, now);              // 顾客退回 仅 wh_to → 不计
        insertLedger("st-ledger-5", 3, "ST-P2", 2, null, -1, null,
                monthStart.atStartOfDay().minusHours(1));                          // SELL 上月末 → 不计
        insertLedger("st-ledger-6", 5, "ST-S3", 1, 2, 0, null, now);              // TRANSFER 类型外 → 不计
        insertLedger("st-ledger-7", 8, "ST-V1", 2, null, -1, null, now);          // VOID 类型外 → 不计

        // 最近成功批次：DONE 9/1；更晚的 FAILED 9/2（不取）与更早的 DONE 8/15（不取）
        insertBatch('a', "orders-latest.csv", 1, LocalDateTime.of(2026, 9, 1, 10, 15, 30));
        insertBatch('b', "broken.csv", 2, LocalDateTime.of(2026, 9, 2, 9, 0, 0));
        insertBatch('c', "orders-old.csv", 1, LocalDateTime.of(2026, 8, 15, 8, 0, 0));
    }

    /** 本月入库期望值：月归属随运行日漂移的种子（today−3 等）按 monthStart 现算。 */
    private static long monthInboundOf(LocalDate monthStart, LocalDate... inDates) {
        long count = 0;
        for (LocalDate d : inDates) {
            if (d != null && !d.isBefore(monthStart)) {
                count++;
            }
        }
        return count;
    }

    @Test
    void dashboard_fleetAggregatesAndYahooCard_viewerReadable() throws Exception {
        LocalDate today = LocalDate.now(clock);
        LocalDate monthStart = today.withDayOfMonth(1);
        seedAll(today);

        // 期望值推导（固定阈值 10/20）：
        // totalItems=8 活件；在途1 在库5 已出库2；滞销黄=S3(龄15∈[10,20)) 红=S2(龄40≥20)
        // （S4 sale=2 排除、S5 龄3<10、S1 龄0）；货值=1500+2000+3000+4000+1000=11500；
        // 库龄=(0+40+15+3+3)/5=12.2（在库且有入库日的 5 件，S4/S5 计入——口径仅滞销按销售态过滤）；
        // 出库=SELL+SCRAP+退回拍卖场 本月 3 行（顾客退回/上月/TRANSFER/VOID 不计）
        String body = mockMvc.perform(get("/api/stats/dashboard").session(loginAs("miru")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fleet.totalItems").value(8))
                .andExpect(jsonPath("$.data.fleet.inTransit").value(1))
                .andExpect(jsonPath("$.data.fleet.inStock").value(5))
                .andExpect(jsonPath("$.data.fleet.shipped").value(2))
                .andExpect(jsonPath("$.data.fleet.monthInbound").value(monthInboundOf(monthStart,
                        today, today.minusDays(40), today.minusDays(15),
                        today.minusDays(3), today.minusDays(3), today.minusDays(2), today.minusDays(50))))
                .andExpect(jsonPath("$.data.fleet.monthOutbound").value(3))
                .andExpect(jsonPath("$.data.fleet.slowWarn").value(1))
                .andExpect(jsonPath("$.data.fleet.slowRed").value(1))
                .andExpect(jsonPath("$.data.fleet.stockValue").value(11500))
                .andExpect(jsonPath("$.data.fleet.avgStockAgeDays").value(12.2))
                .andExpect(jsonPath("$.data.yahoo.soldNotShipped").value(1))
                .andExpect(jsonPath("$.data.yahoo.canceledNotRelisted").value(1))
                .andExpect(jsonPath("$.data.yahoo.withdrawNeeded").value(1))
                .andExpect(jsonPath("$.data.yahoo.lastImportFilename").value("orders-latest.csv"))
                .andReturn().getResponse().getContentAsString();
        // fleet 行 warehouse=null（全仓标记）；新鲜度=最近 DONE 批次（FAILED 更晚也不取）
        assertThat(body).contains("\"warehouse\":null")
                .contains("2026-09-01T10:15:30");
    }

    @Test
    void warehouses_perWarehouseBreakdown_editorReadable() throws Exception {
        LocalDate today = LocalDate.now(clock);
        LocalDate monthStart = today.withDayOfMonth(1);
        seedAll(today);
        MockHttpSession editor = loginAs("eichi");

        mockMvc.perform(get("/api/stats/warehouses").session(editor))
                .andExpect(status().isOk())
                // 名古屋（1）：T1 在途 + S1/S4/S5 在库（S4 成交、S5 取消——均无滞销、计入库龄货值）
                // 货值=1500+4000+1000=6500；库龄=(0+3+3)/3=2.0；出库=SCRAP+退回拍卖场 2 行
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].warehouse").value(1))
                .andExpect(jsonPath("$.data[0].totalItems").value(4))
                .andExpect(jsonPath("$.data[0].inTransit").value(1))
                .andExpect(jsonPath("$.data[0].inStock").value(3))
                .andExpect(jsonPath("$.data[0].shipped").value(0))
                .andExpect(jsonPath("$.data[0].monthInbound").value(monthInboundOf(monthStart,
                        today, today.minusDays(3), today.minusDays(3))))
                .andExpect(jsonPath("$.data[0].monthOutbound").value(2))
                .andExpect(jsonPath("$.data[0].slowWarn").value(0))
                .andExpect(jsonPath("$.data[0].slowRed").value(0))
                .andExpect(jsonPath("$.data[0].stockValue").value(6500))
                .andExpect(jsonPath("$.data[0].avgStockAgeDays").value(2.0))
                // 福岡（2）：S2/S3 在库 + P1/P2 已出库；黄1 红1；
                // 货值=2000+3000=5000；库龄=(40+15)/2=27.5；出库=SELL 1 行
                .andExpect(jsonPath("$.data[1].warehouse").value(2))
                .andExpect(jsonPath("$.data[1].totalItems").value(4))
                .andExpect(jsonPath("$.data[1].inTransit").value(0))
                .andExpect(jsonPath("$.data[1].inStock").value(2))
                .andExpect(jsonPath("$.data[1].shipped").value(2))
                .andExpect(jsonPath("$.data[1].monthInbound").value(monthInboundOf(monthStart,
                        today.minusDays(15), today.minusDays(2), today.minusDays(50))))
                .andExpect(jsonPath("$.data[1].monthOutbound").value(1))
                .andExpect(jsonPath("$.data[1].slowWarn").value(1))
                .andExpect(jsonPath("$.data[1].slowRed").value(1))
                .andExpect(jsonPath("$.data[1].stockValue").value(5000))
                .andExpect(jsonPath("$.data[1].avgStockAgeDays").value(27.5));
    }

    @Test
    void dashboard_emptyAndNoDoneBatch_zeroedWithNullFreshness() throws Exception {
        // 空库 + 无成功批次：聚合归零、件均库龄 null、新鲜度双 null（前端显示「—」）
        String body = mockMvc.perform(get("/api/stats/dashboard").session(loginAs("boss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fleet.totalItems").value(0))
                .andExpect(jsonPath("$.data.fleet.inStock").value(0))
                .andExpect(jsonPath("$.data.fleet.monthInbound").value(0))
                .andExpect(jsonPath("$.data.fleet.monthOutbound").value(0))
                .andExpect(jsonPath("$.data.fleet.stockValue").value(0))
                .andExpect(jsonPath("$.data.yahoo.soldNotShipped").value(0))
                .andExpect(jsonPath("$.data.yahoo.withdrawNeeded").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"avgStockAgeDays\":null")
                .contains("\"lastImportFilename\":null")
                .contains("\"lastImportFinishedAt\":null");
    }
}
