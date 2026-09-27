package com.kcgl;

import com.kcgl.module.inventory.LedgerConsistencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对账不变量集成测试（M3-②，docs/01 5.3 唯一定义）：
 * 每仓 Σ(流水双向入账) ≡ COUNT(在库未删未废)；wh_to 记 +1、wh_from 记 −1、NULL 不入账。
 * 不变量先于 M3 动作端点落地——后续每个端点的验收标准已就绪；
 * 每日自检 job（M3-⑦）与 restore --verify、管理后台「帳実自検」复用同一服务。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class LedgerConsistencyIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String PASSWORD = "Item-1234-x";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    LedgerConsistencyService consistency;

    long venueId;

    @BeforeEach
    void resetFixtures() {
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM stock_ledger");
        jdbcTemplate.update("DELETE FROM item");
        jdbcTemplate.update("DELETE FROM seq_item_code");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN ('eichi')");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('eichi', ?, '編集者', 2, 1, 0)
                """, ENCODER.encode(PASSWORD));
        jdbcTemplate.update("DELETE FROM auction_venue");
        jdbcTemplate.update("DELETE FROM price_band");
        jdbcTemplate.update("DELETE FROM year_code");
        jdbcTemplate.update("INSERT INTO year_code(`year`, code) VALUES (2016,'A'),(2026,'K'),(2027,'L')");
        jdbcTemplate.update("INSERT INTO price_band(code, lower_bound, upper_bound, enabled) VALUES ('X', 0, 3000, 1)");
        jdbcTemplate.update("INSERT INTO auction_venue(code, name, enabled) VALUES ('HT', '飛騨古民具市', 1)");
        venueId = jdbcTemplate.queryForObject("SELECT id FROM auction_venue WHERE code = 'HT'", Long.class);
    }

    // ------------------------------------------------------------- 夹具与助手

    private MockHttpSession loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .param("username", username).param("password", PASSWORD))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("登录应成功: %s",
                result.getResponse().getContentAsString()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession();
    }

    /** 录一件在途（预计仓 warehouse）并返回 id。 */
    private long createItem(MockHttpSession session, String clientReqId, String warehouse) throws Exception {
        String json = mockMvc.perform(post("/api/items").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"" + clientReqId + "\",\"venueId\":" + venueId
                                + ",\"buyDate\":\"2026-09-15\",\"purchasePrice\":1000,\"warehouse\":"
                                + warehouse + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    /** 单件到仓确认；warehouse 非空=到仓改仓。 */
    private void arrive(MockHttpSession session, long itemId, String clientReqId, Integer warehouse)
            throws Exception {
        String line = "{\"itemId\":" + itemId + ",\"clientReqId\":\"" + clientReqId + "\""
                + (warehouse == null ? "" : ",\"warehouse\":" + warehouse) + "}";
        mockMvc.perform(post("/api/inventory/arrivals").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[" + line + "]}"))
                .andExpect(status().isOk());
    }

    private void voidItem(MockHttpSession session, long itemId, String clientReqId, String reason)
            throws Exception {
        mockMvc.perform(post("/api/items/" + itemId + "/void").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"" + clientReqId + "\",\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk());
    }

    private LedgerConsistencyService.WarehouseBalance balanceOf(
            LedgerConsistencyService.Report report, int warehouse) {
        return report.balances().stream()
                .filter(b -> b.warehouse() == warehouse)
                .findFirst().orElseThrow();
    }

    // ------------------------------------------------------------- 不变量成立

    @Test
    void invariantHolds_acrossCreateArriveVoid() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long a = createItem(editor, "chk-a", "1");
        long b = createItem(editor, "chk-b", "1");
        long c = createItem(editor, "chk-c", "2");

        // 全在途：CREATE 行不占仓账（wh 两端 NULL），两仓 0/0
        LedgerConsistencyService.Report r = consistency.check();
        assertThat(r.ok()).isTrue();
        assertThat(r.drifts()).isEmpty();
        assertThat(r.balances()).hasSize(2);
        assertThat(balanceOf(r, 1)).isEqualTo(new LedgerConsistencyService.WarehouseBalance(1, 0, 0));
        assertThat(balanceOf(r, 2)).isEqualTo(new LedgerConsistencyService.WarehouseBalance(2, 0, 0));

        // a 入仓1（ARRIVAL +1）；b 到仓改仓2（在途预计仓不占账→改仓无流水，+1 记实际仓）
        arrive(editor, a, "chk-arr-a", null);
        arrive(editor, b, "chk-arr-b", 2);
        r = consistency.check();
        assertThat(r.ok()).isTrue();
        assertThat(balanceOf(r, 1)).isEqualTo(new LedgerConsistencyService.WarehouseBalance(1, 1, 1));
        assertThat(balanceOf(r, 2)).isEqualTo(new LedgerConsistencyService.WarehouseBalance(2, 1, 1));

        // 作废在库件 a：VOID 记 (仓1,−1) → 仓1 归零（行侧 voided 不再计入真值）
        voidItem(editor, a, "chk-void-a", "誤入力");
        r = consistency.check();
        assertThat(r.ok()).isTrue();
        assertThat(balanceOf(r, 1)).isEqualTo(new LedgerConsistencyService.WarehouseBalance(1, 0, 0));
        assertThat(balanceOf(r, 2)).isEqualTo(new LedgerConsistencyService.WarehouseBalance(2, 1, 1));

        // 作废在途件 c：不占仓账，两侧均无影响
        voidItem(editor, c, "chk-void-c", "誤入力");
        r = consistency.check();
        assertThat(r.ok()).isTrue();
        assertThat(balanceOf(r, 2)).isEqualTo(new LedgerConsistencyService.WarehouseBalance(2, 1, 1));
        assertThat(r.drifts()).isEmpty();
    }

    // ------------------------------------------------------------- 漂移检出

    @Test
    void driftDetected_whenItemRowDriftsFromLedger() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long a = createItem(editor, "chk-d", "1");
        arrive(editor, a, "chk-arr-d", null);
        assertThat(consistency.check().ok()).isTrue();

        // 绕过业务路径直接改行（模拟损坏/手工 SQL 误操作）：在库件仓1→仓2 无流水
        jdbcTemplate.update("UPDATE item SET warehouse = 2 WHERE id = ?", a);

        LedgerConsistencyService.Report r = consistency.check();
        assertThat(r.ok()).isFalse();
        assertThat(r.drifts()).hasSize(1);
        LedgerConsistencyService.ItemDrift drift = r.drifts().get(0);
        assertThat(drift.itemId()).isEqualTo(a);
        assertThat(drift.itemCode()).isEqualTo("HTK9-A1X");
        // 流水头寸说它在仓1，行状态说它在仓2
        assertThat(drift.ledgerPositions()).containsEntry(1, 1L);
        assertThat(drift.itemWarehouse()).isEqualTo(2);
        assertThat(drift.itemStockStatus()).isEqualTo(1);
        // 仓级聚合两侧同时失配（各差 1）
        assertThat(balanceOf(r, 1).ledgerSum()).isEqualTo(1);
        assertThat(balanceOf(r, 1).itemCount()).isZero();
        assertThat(balanceOf(r, 2).ledgerSum()).isZero();
        assertThat(balanceOf(r, 2).itemCount()).isEqualTo(1);
    }
}
