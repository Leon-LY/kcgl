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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 手工修正集成测试（D4，docs/01 7.2 矩阵「手工修正（A，须 reason）」两轴 任意→任意）。
 *
 * <p>它的价值全在「绕开边表」这一条：故本测试重点不是常规五元组，而是
 * (a) 边表禁止的迁移能一步到位（在途→已出库，无需先到仓）；
 * (b) 销售态的「单调只前进」是自动标记动作的性质，本动作可以合法回退（成交→在售）；
 * (c) 越权代价被 A-only + reason 必填 + before/after 审计 + 对账不变量兜住——
 * 每一例修正后都跑 consistency.check()，因为「按边推导 qty」写错就会破账实一致。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class AdjustIntegrationTest {

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
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN ('boss','eichi','miru')");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('boss', ?, '管理者', 1, 1, 0), ('eichi', ?, '編集者', 2, 1, 0), ('miru', ?, '閲覧者', 3, 1, 0)
                """, ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD));
        jdbcTemplate.update("DELETE FROM auction_venue");
        jdbcTemplate.update("DELETE FROM price_band");
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

    /** 录一件在途（预计仓库 1）并返回 id。 */
    private long createItem(MockHttpSession session, String clientReqId) throws Exception {
        String json = mockMvc.perform(post("/api/items").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"" + clientReqId + "\",\"venueId\":" + venueId
                                + ",\"buyDate\":\"2026-09-15\",\"purchasePrice\":1000,\"warehouse\":1,"
                                + "\"remark\":\"修正テスト\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private void arrive(MockHttpSession session, long itemId, String clientReqId) throws Exception {
        mockMvc.perform(post("/api/inventory/arrivals").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"itemId\":" + itemId + ",\"clientReqId\":\"" + clientReqId + "\"}]}"))
                .andExpect(status().isOk());
    }

    /** 手工修正请求体；两轴传 null 即省略该键（=保持不变）。 */
    private String adjustBody(String clientReqId, String reason, Integer stockStatus, Integer saleStatus) {
        StringBuilder sb = new StringBuilder("{\"clientReqId\":\"").append(clientReqId).append("\"");
        if (reason != null) {
            sb.append(",\"reason\":\"").append(reason).append("\"");
        }
        if (stockStatus != null) {
            sb.append(",\"stockStatus\":").append(stockStatus);
        }
        if (saleStatus != null) {
            sb.append(",\"saleStatus\":").append(saleStatus);
        }
        return sb.append("}").toString();
    }

    private String adjust(MockHttpSession session, long itemId, String body) throws Exception {
        return mockMvc.perform(post("/api/items/" + itemId + "/adjust").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
    }

    private Map<String, Object> ledger(long itemId) {
        return jdbcTemplate.queryForMap("""
                SELECT stock_from, stock_to, sale_from, sale_to, wh_from, wh_to, qty_change,
                       reason, client_req_id
                FROM stock_ledger WHERE item_id = ? AND txn_type = 12
                """, itemId);
    }

    private static int v(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).intValue();
    }

    private long auditCount(long itemId, String action) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = ? AND entity_id = ?", Long.class,
                action, itemId);
    }

    // ------------------------------------------------------------- 任意→任意（本动作的核心）

    @Test
    void adjust_transitToOutInOneStep_isTheWholePoint() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "adj-t2o");

        // 边表里没有这条边（在途件不可直接卖出/报废），手工修正一步到位
        String body = adjust(boss, id, adjustBody("adj-t2o-key", "棚卸差異の是正", 2, null));
        assertThat(body).contains("\"stockStatus\":2");

        Map<String, Object> item = jdbcTemplate.queryForMap(
                "SELECT stock_status, sale_status, version FROM item WHERE id = ?", id);
        assertThat(v(item, "stock_status")).isEqualTo(2);
        assertThat(v(item, "sale_status")).isEqualTo(0); // 未指定的轴保持不动
        assertThat(v(item, "version")).isEqualTo(1);      // 录入初值 0（ItemCodeTxService setVersion(0)）、修正 +1

        // 在途→已出库两态都不占仓账 → qty 0 且 wh 全 NULL
        Map<String, Object> row = ledger(id);
        assertThat(v(row, "stock_from")).isEqualTo(0);
        assertThat(v(row, "stock_to")).isEqualTo(2);
        assertThat(row.get("wh_from")).isNull();
        assertThat(row.get("wh_to")).isNull();
        assertThat(v(row, "qty_change")).isEqualTo(0);
        assertThat(row.get("reason")).isEqualTo("棚卸差異の是正");
        assertThat(row.get("client_req_id")).isEqualTo("adj-t2o-key");

        assertThat(auditCount(id, "ITEM_ADJUST")).isEqualTo(1);
        assertThat(consistency.check().ok()).as("在途→已出库后账实一致").isTrue();
    }

    @Test
    void adjust_inStockToOut_recordsMinusOneAtWarehouse() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "adj-i2o");
        arrive(boss, id, "adj-i2o-arr");

        adjust(boss, id, adjustBody("adj-i2o-key", "実物は倉庫に無い", 2, null));

        Map<String, Object> row = ledger(id);
        assertThat(v(row, "stock_from")).isEqualTo(1);
        assertThat(v(row, "stock_to")).isEqualTo(2);
        assertThat(v(row, "wh_from")).isEqualTo(1); // 腾出该仓
        assertThat(row.get("wh_to")).isNull();
        assertThat(v(row, "qty_change")).isEqualTo(-1);
        assertThat(consistency.check().ok()).as("在库→已出库后账实一致").isTrue();
    }

    @Test
    void adjust_outToInStock_recordsPlusOneAtWarehouse() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "adj-o2i");
        arrive(boss, id, "adj-o2i-arr");
        adjust(boss, id, adjustBody("adj-o2i-out", "誤って出庫扱い", 2, null));

        adjust(boss, id, adjustBody("adj-o2i-key", "実物は在庫", 1, null));

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT stock_from, stock_to, wh_from, wh_to, qty_change
                FROM stock_ledger WHERE item_id = ? AND txn_type = 12 AND client_req_id = 'adj-o2i-key'
                """, id);
        assertThat(v(row, "stock_from")).isEqualTo(2);
        assertThat(v(row, "stock_to")).isEqualTo(1);
        assertThat(row.get("wh_from")).isNull();
        assertThat(v(row, "wh_to")).isEqualTo(1); // 回到该仓
        assertThat(v(row, "qty_change")).isEqualTo(1);
        assertThat(consistency.check().ok()).as("已出库→在库后账实一致").isTrue();
    }

    @Test
    void adjust_saleOnly_recordsZeroQtyAndNoWarehouse() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "adj-sale");
        arrive(boss, id, "adj-sale-arr");

        adjust(boss, id, adjustBody("adj-sale-key", "販売状態の誤り", null, 1));

        Map<String, Object> row = ledger(id);
        assertThat(v(row, "stock_from")).isEqualTo(1); // 库存轴未指定 → 原值
        assertThat(v(row, "stock_to")).isEqualTo(1);
        assertThat(v(row, "sale_from")).isEqualTo(0);
        assertThat(v(row, "sale_to")).isEqualTo(1);
        assertThat(row.get("wh_from")).isNull();
        assertThat(row.get("wh_to")).isNull();
        assertThat(v(row, "qty_change")).isEqualTo(0);
        assertThat(consistency.check().ok()).as("仅改销售态后账实一致").isTrue();
    }

    @Test
    void adjust_saleStatus_canGoBackwards_unlikeAutoMarkers() throws Exception {
        // 自动标记动作的销售态是单调只前进（防陈旧受注重传回退）；手工修正的矩阵语义是
        // 「任意→任意」，成交→在售这类回退正是它的合法用途（例如误标成交后的纠正）。
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "adj-back");
        arrive(boss, id, "adj-back-arr");
        adjust(boss, id, adjustBody("adj-back-fwd", "誤って成交", null, 2));

        adjust(boss, id, adjustBody("adj-back-key", "落札は誤りだった", null, 1));

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT sale_from, sale_to, qty_change FROM stock_ledger
                WHERE item_id = ? AND txn_type = 12 AND client_req_id = 'adj-back-key'
                """, id);
        assertThat(v(row, "sale_from")).isEqualTo(2);
        assertThat(v(row, "sale_to")).isEqualTo(1); // 回退被允许
        assertThat(v(row, "qty_change")).isEqualTo(0);
        assertThat(consistency.check().ok()).isTrue();
    }

    // ------------------------------------------------------------- 幂等与入参

    @Test
    void adjust_idempotentReplay_singleLedgerAndAudit_keyMisuse400() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "adj-rp");
        long other = createItem(boss, "adj-rp-o");

        String body = adjustBody("adj-rp-key", "再送信テスト", 2, null);
        String first = adjust(boss, id, body);
        String second = adjust(boss, id, body);
        assertThat(second).isEqualTo(first); // 重放=原结果 200 出清
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 12", Long.class)).isEqualTo(1);
        assertThat(auditCount(id, "ITEM_ADJUST")).isEqualTo(1);
        assertThat(v(jdbcTemplate.queryForMap("SELECT version FROM item WHERE id = ?", id), "version"))
                .isEqualTo(1); // 重放不再推进版本

        // 同键打到别的商品 → 400（防脏读）
        mockMvc.perform(post("/api/items/" + other + "/adjust").session(boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody("adj-rp-key", "別商品", 2, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
    }

    @Test
    void adjust_missingReason400_blankReason400() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "adj-reason");

        // reason 键缺失
        mockMvc.perform(post("/api/items/" + id + "/adjust").session(boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody("adj-reason-key1", null, 2, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        // reason 空白串
        mockMvc.perform(post("/api/items/" + id + "/adjust").session(boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody("adj-reason-key2", "   ", 2, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        // 录件自带 CREATE(1) 流水，故「没写修正流水」须按 txn_type 限定，裸计数会误判
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 12", Long.class)).isEqualTo(0);
    }

    @Test
    void adjust_noAxis400_invalidAxis400_sameState400_zeroSideEffects() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "adj-bad");

        // 两轴都不给：修正没有目标
        mockMvc.perform(post("/api/items/" + id + "/adjust").session(boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody("adj-bad-1", "対象なし", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        // 库存态越域（合法 0..2）
        mockMvc.perform(post("/api/items/" + id + "/adjust").session(boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody("adj-bad-2", "範囲外", 5, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        // 销售态越域（合法 0..3）
        mockMvc.perform(post("/api/items/" + id + "/adjust").session(boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody("adj-bad-3", "範囲外", null, 9)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        // 给的值与现值全同 = 没有修正，不写流水（防零变化污染台账）
        mockMvc.perform(post("/api/items/" + id + "/adjust").session(boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody("adj-bad-4", "変化なし", 0, 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 12", Long.class)).isEqualTo(0);
        assertThat(auditCount(id, "ITEM_ADJUST")).isEqualTo(0);
    }

    // ------------------------------------------------------------- 权限与冻结态

    @Test
    void adjust_byViewer403_byEditor403_adminOnly() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "adj-perm");

        for (String user : new String[] {"eichi", "miru"}) {
            MockHttpSession session = loginAs(user);
            mockMvc.perform(post("/api/items/" + id + "/adjust").session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(adjustBody("adj-perm-" + user, "越権テスト", 2, null)))
                    .andExpect(status().isForbidden());
        }
        // 编辑者被拒是重点：普通动作都是 E+，唯独手工修正是 A-only
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 12", Long.class)).isEqualTo(0);
    }

    @Test
    void adjust_voided409006_deleted404() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long voided = createItem(boss, "adj-void");
        long deleted = createItem(boss, "adj-del");

        mockMvc.perform(post("/api/items/" + voided + "/void").session(boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"adj-void-key\",\"reason\":\"誤登録\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/items/" + deleted).session(boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"adj-del-key\"}"))
                .andExpect(status().isOk());

        // 作废=冻结禁一切迁移；误作废走重录，不从这里解冻
        mockMvc.perform(post("/api/items/" + voided + "/adjust").session(boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody("adj-void-adj", "解凍テスト", 1, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409006));
        // 软删件按不存在处理；误软删走恢复
        mockMvc.perform(post("/api/items/" + deleted + "/adjust").session(boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody("adj-del-adj", "復帰テスト", 1, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404001));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 12", Long.class)).isEqualTo(0);
    }
}
