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

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 治理查询集成测试（M5-④）：全库流水浏览（GET /inventory/ledgers）与
 * 操作日志查询（GET /operation-logs）——管理员专用、id 倒序分页、
 * 筛选语义（等值/LIKE 前缀/仓库双侧命中/时间闭开区间）。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class GovernanceIntegrationTest {

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

    long bossId;
    long eichiId;
    long htVenueId;

    @BeforeEach
    void resetFixtures() {
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM stock_ledger");
        jdbcTemplate.update("DELETE FROM item");
        jdbcTemplate.update("DELETE FROM seq_item_code");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN ('boss','eichi','miru')");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('boss', ?, '管理者', 1, 1, 0), ('eichi', ?, '編集者', 2, 1, 0), ('miru', ?, '閲覧者', 3, 1, 0)
                """, ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD));
        bossId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = 'boss'", Long.class);
        eichiId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = 'eichi'", Long.class);
        jdbcTemplate.update("DELETE FROM auction_venue");
        jdbcTemplate.update("DELETE FROM price_band");
        jdbcTemplate.update(
                "INSERT INTO price_band(code, lower_bound, upper_bound, enabled) VALUES ('X', 0, 3000, 1)");
        jdbcTemplate.update(
                "INSERT INTO auction_venue(code, name, enabled) VALUES ('HT', '飛騨古民具市', 1)");
        htVenueId = jdbcTemplate.queryForObject("SELECT id FROM auction_venue WHERE code = 'HT'", Long.class);

        // 三件商品（HTK5 桶）+ 五条流水：不同类型/仓库/操作人/时间
        insertItem("HTK5-A1X", 1);
        insertItem("HTK5-A2X", 2);
        insertItem("HTZ5-A1X", 1);
        LocalDateTime base = LocalDateTime.of(2026, 9, 28, 10, 0);
        insertLedger("r-1", 1, "HTK5-A1X", null, null, 1, null, bossId, base);              // 录入
        insertLedger("r-2", 2, "HTK5-A1X", null, 1, 0, null, eichiId, base.plusMinutes(1)); // 到仓
        insertLedger("r-3", 5, "HTK5-A1X", 1, 2, 0, null, eichiId, base.plusMinutes(2));    // 调拨 1→2
        insertLedger("r-4", 3, "HTK5-A2X", 2, null, -1, null, bossId, base.plusMinutes(3)); // 卖出（2 仓出）
        insertLedger("r-5", 8, "HTZ5-A1X", null, null, 0, null, eichiId, base.plusMinutes(4)); // 作废

        // 操作日志：三行不同动作/操作人
        insertLog("ITEM_VOID", "item", 1L, "{\"reason\":\"価格入力ミス\"}", bossId, "管理者", base.plusMinutes(4));
        insertLog("SETTING_UPDATE", "sys_setting", null, "{\"key\":\"label.width\"}", bossId, "管理者", base.plusMinutes(5));
        insertLog("ITEM_TRANSFER", "item", 2L, "{\"whFrom\":1,\"whTo\":2}", eichiId, "編集者", base.plusMinutes(2));
    }

    private void insertItem(String code, int warehouse) {
        jdbcTemplate.update("""
                INSERT INTO item(item_code, venue_id, venue_code, buy_month,
                  seq_prefix, seq_no, buy_date, purchase_price, price_band_code, warehouse,
                  stock_status, sale_status, voided, deleted, created_by)
                VALUES (?, ?, 'HT', ?, 'A', 1, ?, 1000, 'X', ?, 1, 0, 0, 0, ?)
                """, code, htVenueId, BUY_DATE.getMonthValue(), BUY_DATE, warehouse, bossId);
    }

    private void insertLedger(String reqId, int txnType, String itemCode, Integer whFrom, Integer whTo,
            int qtyChange, Integer returnDirection, long operatorId, LocalDateTime createdAt) {
        long itemId = jdbcTemplate.queryForObject(
                "SELECT id FROM item WHERE item_code = ?", Long.class, itemCode);
        jdbcTemplate.update("""
                INSERT INTO stock_ledger(client_req_id, txn_type, item_id, item_code,
                  wh_from, wh_to, qty_change, return_direction, operator_id, operator_name, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, (SELECT display_name FROM sys_user WHERE id = ?), ?)
                """, reqId, txnType, itemId, itemCode, whFrom, whTo, qtyChange, returnDirection,
                operatorId, operatorId, createdAt);
    }

    private void insertLog(String action, String entityType, Long entityId, String detail,
            long operatorId, String operatorName, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO operation_log(action, entity_type, entity_id, detail,
                  operator_id, operator_name, ip, ua, created_at)
                VALUES (?, ?, ?, CAST(? AS JSON), ?, ?, '127.0.0.1', 'test-ua', ?)
                """, action, entityType, entityId, detail, operatorId, operatorName, createdAt);
    }

    private MockHttpSession loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .param("username", username).param("password", PASSWORD))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("登录应成功: %s",
                result.getResponse().getContentAsString()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession();
    }

    @Test
    void adminBrowsesLedgersNewestFirstWithFullRowShape() throws Exception {
        mockMvc.perform(get("/api/inventory/ledgers").session(loginAs("boss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.rows.length()").value(5))
                // id 倒序：最新（作废 HTZ5-A1X）在前
                .andExpect(jsonPath("$.data.rows[0].itemCode").value("HTZ5-A1X"))
                .andExpect(jsonPath("$.data.rows[0].txnType").value(8))
                .andExpect(jsonPath("$.data.rows[0].operatorName").value("編集者"))
                .andExpect(jsonPath("$.data.rows[4].txnType").value(1))
                .andExpect(jsonPath("$.data.rows[2].whFrom").value(1))
                .andExpect(jsonPath("$.data.rows[2].whTo").value(2))
                .andExpect(jsonPath("$.data.rows[0].clientReqId").value("r-5"));
    }

    @Test
    void ledgerFiltersNarrowByTypeCodePrefixWarehouseAndTimeWindow() throws Exception {
        // 类型筛选：只看卖出
        mockMvc.perform(get("/api/inventory/ledgers")
                        .param("txnType", "3").session(loginAs("boss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].itemCode").value("HTK5-A2X"))
                .andExpect(jsonPath("$.data.rows[0].whFrom").value(2));

        // 前缀：HTK5 命中 4 条（HTZ5 排除）
        mockMvc.perform(get("/api/inventory/ledgers")
                        .param("itemCode", "HTK5").session(loginAs("boss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4));

        // 仓库=2：调拨到侧（r-3）与卖出出侧（r-4）双侧命中
        mockMvc.perform(get("/api/inventory/ledgers")
                        .param("warehouse", "2").session(loginAs("boss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        // 时间闭开区间 [from, to)：from 含 10:00 的 r-1，to 不含 10:02 的 r-3（边界即语义）
        mockMvc.perform(get("/api/inventory/ledgers")
                        .param("from", "2026-09-28T10:00:00")
                        .param("to", "2026-09-28T10:02:00")
                        .session(loginAs("boss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.rows[0].clientReqId").value("r-2"))
                .andExpect(jsonPath("$.data.rows[1].clientReqId").value("r-1"));

        // 操作人筛选：boss 名下 2 条
        mockMvc.perform(get("/api/inventory/ledgers")
                        .param("operatorName", "管理者").session(loginAs("boss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    void ledgerPaginationSlicesWithoutLosingTotal() throws Exception {
        mockMvc.perform(get("/api/inventory/ledgers")
                        .param("page", "1").param("size", "2").session(loginAs("boss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2));
        // 第二页从第 3 新开始
        mockMvc.perform(get("/api/inventory/ledgers")
                        .param("page", "2").param("size", "2").session(loginAs("boss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].clientReqId").value("r-3"));
    }

    @Test
    void ledgersAreAdminOnly() throws Exception {
        mockMvc.perform(get("/api/inventory/ledgers").session(loginAs("eichi")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inventory/ledgers").session(loginAs("miru")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminQueriesOperationLogsNewestFirst() throws Exception {
        mockMvc.perform(get("/api/operation-logs").session(loginAs("boss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                // id 倒序：ITEM_TRANSFER 最后插入（id 最大）在前
                .andExpect(jsonPath("$.data.rows[0].action").value("ITEM_TRANSFER"))
                .andExpect(jsonPath("$.data.rows[0].operatorName").value("編集者"))
                .andExpect(jsonPath("$.data.rows[1].action").value("SETTING_UPDATE"))
                .andExpect(jsonPath("$.data.rows[1].entityType").value("sys_setting"))
                .andExpect(jsonPath("$.data.rows[1].operatorName").value("管理者"))
                .andExpect(jsonPath("$.data.rows[2].action").value("ITEM_VOID"))
                .andExpect(jsonPath("$.data.rows[0].detail").isNotEmpty());
    }

    @Test
    void operationLogFiltersNarrowByActionAndOperator() throws Exception {
        mockMvc.perform(get("/api/operation-logs")
                        .param("action", "ITEM_VOID").session(loginAs("boss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].entityType").value("item"));

        // 前缀收窄（UI 明示「前方一致」；曾误配 eq 被 E2E 拦截——此处锁死语义）
        mockMvc.perform(get("/api/operation-logs")
                        .param("action", "ITEM_").session(loginAs("boss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.rows[0].action").value("ITEM_TRANSFER"))
                .andExpect(jsonPath("$.data.rows[1].action").value("ITEM_VOID"));

        mockMvc.perform(get("/api/operation-logs")
                        .param("operatorName", "編集者").session(loginAs("boss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].action").value("ITEM_TRANSFER"));
    }

    @Test
    void operationLogsAreAdminOnly() throws Exception {
        mockMvc.perform(get("/api/operation-logs").session(loginAs("eichi")))
                .andExpect(status().isForbidden());
    }
}
