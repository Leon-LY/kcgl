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
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 盘点全流程集成测试（M3-⑥，docs/01 7.3）：发起（一仓一单 409009）→ 扫码照记
 * （重复 repeated / 未知 404001 / close 后 409010）→ close 冻结差异四分类+变动
 * 标注 → CONFIRM 入账（STOCKTAKE_ADJUST 流水 wh 引用现仓）→ IGNORE 状态幂等 →
 * 全处理完自动转已确认；对账自检穿插各阶段——差异确认端点即不变量的实现。
 * 三角色权限矩阵 + 冻结品禁 CONFIRM + 同键重放 200 出清（docs/01 7.0）。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class StocktakeIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String PASSWORD = "Stk-1234-x";

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
        jdbcTemplate.update("DELETE FROM stocktake_diff");
        jdbcTemplate.update("DELETE FROM stocktake_scan");
        jdbcTemplate.update("DELETE FROM stocktake");
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

    /** 录一件在途（指定预计仓库）并返回 [id, itemCode]。 */
    private String[] createItem(MockHttpSession session, String clientReqId, int warehouse) throws Exception {
        String json = mockMvc.perform(post("/api/items").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"" + clientReqId + "\",\"venueId\":" + venueId
                                + ",\"buyDate\":\"2026-09-15\",\"purchasePrice\":1000,\"warehouse\":" + warehouse
                                + ",\"remark\":\"棚卸テスト\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new String[]{
                json.replaceAll(".*\"id\":(\\d+).*", "$1"),
                json.replaceAll(".*\"itemCode\":\"([^\"]+)\".*", "$1")};
    }

    private void arrive(MockHttpSession session, long itemId, String clientReqId) throws Exception {
        mockMvc.perform(post("/api/inventory/arrivals").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"itemId\":" + itemId + ",\"clientReqId\":\"" + clientReqId + "\"}]}"))
                .andExpect(status().isOk());
    }

    private void voidItem(MockHttpSession session, long itemId, String clientReqId) throws Exception {
        mockMvc.perform(post("/api/items/" + itemId + "/void").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"" + clientReqId + "\",\"reason\":\"二重落札\"}"))
                .andExpect(status().isOk());
    }

    private void transferItem(MockHttpSession session, long itemId, String clientReqId, int toWarehouse)
            throws Exception {
        mockMvc.perform(post("/api/inventory/transfer").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + itemId + ",\"clientReqId\":\"" + clientReqId
                                + "\",\"toWarehouse\":" + toWarehouse + "}"))
                .andExpect(status().isOk());
    }

    private long createStocktake(MockHttpSession session, int warehouse) throws Exception {
        String json = mockMvc.perform(post("/api/stocktakes").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouse\":" + warehouse + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private void scan(MockHttpSession session, long stocktakeId, String code, boolean repeated) throws Exception {
        mockMvc.perform(post("/api/stocktakes/" + stocktakeId + "/scans").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repeated").value(repeated));
    }

    private void close(MockHttpSession session, long stocktakeId) throws Exception {
        mockMvc.perform(post("/api/stocktakes/" + stocktakeId + "/close").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    private String resolve(MockHttpSession session, long stocktakeId, long diffId, String action, String key)
            throws Exception {
        return mockMvc.perform(post("/api/stocktakes/" + stocktakeId + "/diffs/" + diffId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + action + "\",\"clientReqId\":\"" + key + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private long diffIdOf(long stocktakeId, long itemId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM stocktake_diff WHERE stocktake_id = ? AND item_id = ?",
                Long.class, stocktakeId, itemId);
    }

    private Map<String, Object> adjustLedger(long itemId) {
        return jdbcTemplate.queryForMap("""
                SELECT stock_from, stock_to, wh_from, wh_to, qty_change, reason, reason_code,
                       reason_params, ref_type, ref_id, client_req_id
                FROM stock_ledger WHERE item_id = ? AND txn_type = 7
                """, itemId);
    }

    private static int v(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).intValue();
    }

    // ------------------------------------------------------------- 全流程

    @Test
    void fullFlow_scanCloseDiffConfirmIgnore_autoFlipsToConfirmed() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long editorId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'eichi'", Long.class);

        // 五件：A=本仓在库（扫到，对上）；B=本仓在库（未扫，盘亏）；C=盘点期间调拨出仓（扫到，仓错+变动标注）；
        // D=在途（扫到，盘盈）；E=在库后作废（扫到，冻结品）
        long a = Long.parseLong(createItem(editor, "st-a", 1)[0]);
        long b = Long.parseLong(createItem(editor, "st-b", 1)[0]);
        String[] c = createItem(editor, "st-c", 1);
        long d = Long.parseLong(createItem(editor, "st-d", 1)[0]);
        long e = Long.parseLong(createItem(editor, "st-e", 1)[0]);
        arrive(editor, a, "st-a-arr");
        arrive(editor, b, "st-b-arr");
        arrive(editor, Long.parseLong(c[0]), "st-c-arr");
        arrive(editor, e, "st-e-arr");
        voidItem(editor, e, "st-e-void");

        // 发起（JST 当日序号）：PD+日期+两位序号
        mockMvc.perform(post("/api/stocktakes").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouse\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(0))
                .andExpect(jsonPath("$.data.stocktakeNo", matchesPattern("PD\\d{8}-01")))
                .andExpect(jsonPath("$.data.scannedCount").value(0))
                .andExpect(jsonPath("$.data.expectedCount").value(nullValue()))
                .andExpect(jsonPath("$.data.pendingDiffCount").value(nullValue()))
                .andExpect(jsonPath("$.data.createdByName").value("編集者"))
                .andExpect(jsonPath("$.data.mine").value(true));
        long st = jdbcTemplate.queryForObject("SELECT id FROM stocktake", Long.class);

        // 盘点期间变动：C 被调拨到福岡（差异行应带变动标注辅助裁决）
        transferItem(editor, Long.parseLong(c[0]), "st-c-tr", 2);

        // 扫码照记：A 对上；C 他仓；D 非在库；E 冻结——全部记录不拦
        scan(editor, st, jdbcTemplate.queryForObject(
                "SELECT item_code FROM item WHERE id = ?", String.class, a), false);
        scan(editor, st, c[1].toLowerCase(), false); // 全角/小写容错=NFKC+大文字化
        scan(editor, st, jdbcTemplate.queryForObject(
                "SELECT item_code FROM item WHERE id = ?", String.class, d), false);
        scan(editor, st, jdbcTemplate.queryForObject(
                "SELECT item_code FROM item WHERE id = ?", String.class, e), false);
        // 重复扫：repeated=true 不报错，计数不重复累计
        scan(editor, st, c[1], true);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stocktake_scan", Long.class)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stocktake_scan WHERE scanned_by = ?", Long.class, editorId)).isEqualTo(4);
        // 扫码留痕=scan 行本身（双留痕），不逐条写 operation_log
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'STOCKTAKE_SCAN'", Long.class)).isZero();

        // close：冻结期望集合（本仓在库未删未废=A+B）→ 四类差异
        mockMvc.perform(post("/api/stocktakes/" + st + "/close").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.expectedCount").value(2))
                .andExpect(jsonPath("$.data.scannedCount").value(4))
                .andExpect(jsonPath("$.data.diffCount").value(4))
                .andExpect(jsonPath("$.data.pendingDiffCount").value(4))
                .andExpect(jsonPath("$.data.closedByName").value("編集者"));

        // 差异表：待确认优先、按差异行 id（=商品 id 序）稳定排列
        mockMvc.perform(get("/api/stocktakes/" + st + "/diffs").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.rows[0].itemCode").value(
                        jdbcTemplate.queryForObject("SELECT item_code FROM item WHERE id = ?", String.class, b)))
                .andExpect(jsonPath("$.data.rows[0].diffType").value(1))
                .andExpect(jsonPath("$.data.rows[0].expectedWarehouse").value(1))
                .andExpect(jsonPath("$.data.rows[0].actualWarehouse").value(nullValue()))
                .andExpect(jsonPath("$.data.rows[0].note").value(nullValue()))
                .andExpect(jsonPath("$.data.rows[1].diffType").value(3))
                .andExpect(jsonPath("$.data.rows[1].expectedWarehouse").value(2))
                .andExpect(jsonPath("$.data.rows[1].actualWarehouse").value(1))
                .andExpect(jsonPath("$.data.rows[1].note").value("棚卸期間中に変動あり"))
                .andExpect(jsonPath("$.data.rows[2].diffType").value(2))
                .andExpect(jsonPath("$.data.rows[2].expectedWarehouse").value(nullValue()))
                .andExpect(jsonPath("$.data.rows[2].actualWarehouse").value(1))
                .andExpect(jsonPath("$.data.rows[3].diffType").value(4))
                .andExpect(jsonPath("$.data.rows[3].confirmStatus").value(0));

        // close 后不可再扫/再 close（409010）
        mockMvc.perform(post("/api/stocktakes/" + st + "/scans").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + c[1] + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409010));
        mockMvc.perform(post("/api/stocktakes/" + st + "/close").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409010));

        // 冻结品禁 CONFIRM（服务端兜底；前端已隐藏按钮）→ 仅可 IGNORE
        long eDiff = diffIdOf(st, e);
        mockMvc.perform(post("/api/stocktakes/" + st + "/diffs/" + eDiff).session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM\",\"clientReqId\":\"stk-e-ck\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409010));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE item_id = ? AND txn_type = 7", Long.class, e)).isZero();

        // CONFIRM 盘亏：在库→已出库，(现仓,−1)，流水回链差异行
        long bDiff = diffIdOf(st, b);
        mockMvc.perform(post("/api/stocktakes/" + st + "/diffs/" + bDiff).session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM\",\"clientReqId\":\"stk-b-key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.diff.confirmStatus").value(1))
                .andExpect(jsonPath("$.data.result.stockStatus").value(2));
        Map<String, Object> bLedger = adjustLedger(b);
        assertThat(v(bLedger, "stock_from")).isEqualTo(1);
        assertThat(v(bLedger, "stock_to")).isEqualTo(2);
        assertThat(v(bLedger, "wh_from")).isEqualTo(1);
        assertThat(bLedger.get("wh_to")).isNull();
        assertThat(v(bLedger, "qty_change")).isEqualTo(-1);
        assertThat(bLedger.get("ref_type")).isEqualTo("stocktake_diff");
        assertThat(((Number) bLedger.get("ref_id")).longValue()).isEqualTo(bDiff);
        assertThat(bLedger.get("reason").toString()).contains("棚卸調整");
        // 理由结构化（V7，D-130）：日文原文照旧兜底，另落 i18n 键 + 单号参数供前端按语言渲染
        assertThat(bLedger.get("reason_code")).isEqualTo("ledgers.reason.stocktakeAdjust");
        assertThat(bLedger.get("reason_params").toString()).contains("\"no\"");
        // 单件流水端点把它带回前端（详情取引履歴与管理端台帳两处同一口径）
        mockMvc.perform(get("/api/items/" + b + "/ledgers").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].reasonCode")
                        .value("ledgers.reason.stocktakeAdjust"))
                .andExpect(jsonPath("$.data.rows[0].reasonParams").value(
                        org.hamcrest.Matchers.containsString("PD")));
        assertThat(jdbcTemplate.queryForMap(
                "SELECT confirm_status, adjust_ledger_id, confirmed_by FROM stocktake_diff WHERE id = ?",
                bDiff).get("confirm_status")).isEqualTo(1);

        // CONFIRM 仓错：仓 2→1（wh 双侧入账 qty=0）
        long cDiff = diffIdOf(st, Long.parseLong(c[0]));
        mockMvc.perform(post("/api/stocktakes/" + st + "/diffs/" + cDiff).session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM\",\"clientReqId\":\"stk-c-key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.warehouse").value(1));
        Map<String, Object> cLedger = adjustLedger(Long.parseLong(c[0]));
        assertThat(v(cLedger, "wh_from")).isEqualTo(2);
        assertThat(v(cLedger, "wh_to")).isEqualTo(1);
        assertThat(v(cLedger, "qty_change")).isZero();

        // CONFIRM 盘盈：在途→在库，(盘点仓,+1)，商品仓改写为盘点仓
        long dDiff = diffIdOf(st, d);
        mockMvc.perform(post("/api/stocktakes/" + st + "/diffs/" + dDiff).session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM\",\"clientReqId\":\"stk-d-key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.stockStatus").value(1))
                .andExpect(jsonPath("$.data.result.warehouse").value(1));
        Map<String, Object> dLedger = adjustLedger(d);
        assertThat(dLedger.get("wh_from")).isNull();
        assertThat(v(dLedger, "wh_to")).isEqualTo(1);
        assertThat(v(dLedger, "qty_change")).isEqualTo(1);

        // IGNORE 冻结品：仅置位无流水
        mockMvc.perform(post("/api/stocktakes/" + st + "/diffs/" + eDiff).session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"IGNORE\",\"clientReqId\":\"stk-e-key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.diff.confirmStatus").value(2))
                .andExpect(jsonPath("$.data.result").value(nullValue()));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE item_id = ? AND txn_type = 7", Long.class, e)).isZero();

        // 全处理完自动转已确认（1→2）；列表/详情派生计数归零
        mockMvc.perform(get("/api/stocktakes/" + st).session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(2))
                .andExpect(jsonPath("$.data.pendingDiffCount").value(0));
        mockMvc.perform(get("/api/stocktakes").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].status").value(2))
                .andExpect(jsonPath("$.data.rows[0].pendingDiffCount").value(0));

        // 确认后单据锁定：再处理任何差异 → 409010
        mockMvc.perform(post("/api/stocktakes/" + st + "/diffs/" + bDiff).session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"IGNORE\",\"clientReqId\":\"stk-b-ig\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409010));

        // 商品终态：B 已出库；C 回本仓在库；D 在库本仓；E 冻结不变
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_status FROM item WHERE id = ?", Integer.class, b)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT stock_status, warehouse FROM item WHERE id = ?", Long.parseLong(c[0])).get("warehouse"))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT stock_status, warehouse FROM item WHERE id = ?", d))
                .containsEntry("stock_status", 1).containsEntry("warehouse", 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT voided FROM item WHERE id = ?", Integer.class, e)).isEqualTo(1);

        // 审计：发起/close/确认×3/忽略各一条
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'STOCKTAKE_CREATE'", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'STOCKTAKE_CLOSE'", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'STOCKTAKE_DIFF_CONFIRM'", Long.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'STOCKTAKE_DIFF_IGNORE'", Long.class)).isEqualTo(1);

        // 差异确认端点即不变量的实现：全部调整后账实一致
        assertThat(consistency.check().ok()).as("盘点调整后账実一致").isTrue();
    }

    // ------------------------------------------------------------- 幂等（docs/01 7.0）

    @Test
    void resolve_idempotentReplay_sameKeySingleLedger_ignoreStateIdempotent() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        String[] a = createItem(editor, "id-a", 1);
        String[] b = createItem(editor, "id-b", 1);
        arrive(editor, Long.parseLong(a[0]), "id-a-arr");
        arrive(editor, Long.parseLong(b[0]), "id-b-arr");
        long st = createStocktake(editor, 1);
        // 两件均未扫 → 双盘亏差异（b=CONFIRM 键幂等靶，a=IGNORE 状态幂等靶）
        close(editor, st);
        long aDiff = diffIdOf(st, Long.parseLong(a[0]));
        long bDiff = diffIdOf(st, Long.parseLong(b[0]));

        // 同键 CONFIRM 重放：第二次返回原结果语义，流水仅一条、审计不重复
        String first = mockMvc.perform(post("/api/stocktakes/" + st + "/diffs/" + bDiff).session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM\",\"clientReqId\":\"idem-key\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = resolve(editor, st, bDiff, "CONFIRM", "idem-key");
        assertThat(second).isEqualTo(first); // 重放=原结果 200 出清
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE client_req_id = 'idem-key'", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'STOCKTAKE_DIFF_CONFIRM'", Long.class)).isEqualTo(1);

        // IGNORE 状态幂等：重复 IGNORE 返回现状 200（状态即原结果），审计不重复
        mockMvc.perform(post("/api/stocktakes/" + st + "/diffs/" + aDiff).session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"IGNORE\",\"clientReqId\":\"idem-ig-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.diff.confirmStatus").value(2));
        mockMvc.perform(post("/api/stocktakes/" + st + "/diffs/" + aDiff).session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"IGNORE\",\"clientReqId\":\"idem-ig-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.diff.confirmStatus").value(2));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'STOCKTAKE_DIFF_IGNORE'", Long.class)).isEqualTo(1);

        // 键挪用防护：把 CONFIRM 用过的键用在别的差异 → 400
        mockMvc.perform(post("/api/stocktakes/" + st + "/diffs/" + aDiff).session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM\",\"clientReqId\":\"idem-key\"}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------- 一仓一单 / 撤销

    @Test
    void create_oneActivePerWarehouse_conflictsAndCancelRules() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        MockHttpSession boss = loginAs("boss");

        long st = createStocktake(editor, 1);
        mockMvc.perform(post("/api/stocktakes").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouse\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409009));
        // 他仓不受影响：同日序号顺延
        mockMvc.perform(post("/api/stocktakes").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouse\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stocktakeNo", matchesPattern("PD\\d{8}-02")))
                .andExpect(jsonPath("$.data.mine").value(true));

        // 非发起人（含管理员）不可撤：403001
        mockMvc.perform(post("/api/stocktakes/" + st + "/cancel").session(boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403001));

        // 发起人撤自己的单 → 作废；撤销后同仓可再发起
        mockMvc.perform(post("/api/stocktakes/" + st + "/cancel").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(3));
        mockMvc.perform(post("/api/stocktakes/" + st + "/cancel").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409010));
        // 撤销后同仓可再发起：新单即「close 后不可撤」校验的靶
        String st2Json = mockMvc.perform(post("/api/stocktakes").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouse\":1}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long st2 = Long.parseLong(st2Json.replaceAll(".*\"id\":(\\d+).*", "$1"));

        // close 后不可撤（仅进行中可撤）
        String[] a = createItem(editor, "cc-a", 1);
        scan(editor, st2, a[1], false);
        close(editor, st2);
        mockMvc.perform(post("/api/stocktakes/" + st2 + "/cancel").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409010));
    }

    // ------------------------------------------------------------- 扫码边界 / 列表筛选

    @Test
    void scan_unknownCode404_closeWithEmptyWarehouse_stillPendsConfirm() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long st = createStocktake(editor, 2); // 福岡仓空仓盘点
        mockMvc.perform(post("/api/stocktakes/" + st + "/scans").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ZZ99-Z9Z\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404001));
        mockMvc.perform(post("/api/stocktakes/" + st + "/scans").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"  \"}"))
                .andExpect(status().isBadRequest());
        close(editor, st);
        mockMvc.perform(get("/api/stocktakes/" + st).session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.expectedCount").value(0))
                .andExpect(jsonPath("$.data.diffCount").value(0))
                .andExpect(jsonPath("$.data.pendingDiffCount").value(0));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stocktake_diff WHERE stocktake_id = ?", Long.class, st)).isZero();
    }

    @Test
    void diffs_confirmStatusFilter_andRoleMatrix() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        MockHttpSession viewer = loginAs("miru");
        String[] a = createItem(editor, "fl-a", 1);
        String[] b = createItem(editor, "fl-b", 1);
        arrive(editor, Long.parseLong(a[0]), "fl-a-arr");
        arrive(editor, Long.parseLong(b[0]), "fl-b-arr");
        long st = createStocktake(editor, 1);
        scan(editor, st, a[1], false); // A 对上无差异；B 未扫 → 唯一差异=盘亏
        close(editor, st);
        long bDiff = diffIdOf(st, Long.parseLong(b[0]));

        // viewer 只读：列表/详情/差异可看，写操作全 403
        mockMvc.perform(get("/api/stocktakes").session(viewer)).andExpect(status().isOk());
        mockMvc.perform(get("/api/stocktakes/" + st).session(viewer)).andExpect(status().isOk());
        mockMvc.perform(get("/api/stocktakes/" + st + "/diffs").session(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(post("/api/stocktakes").session(viewer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"warehouse\":1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/stocktakes/" + st + "/scans").session(viewer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + a[1] + "\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/stocktakes/" + st + "/close").session(viewer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/stocktakes/" + st + "/diffs/" + bDiff).session(viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM\",\"clientReqId\":\"fl-v\"}"))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 7", Long.class)).isZero();

        // confirmStatus 筛选：处理后待确认 0、已调整 1
        mockMvc.perform(post("/api/stocktakes/" + st + "/diffs/" + bDiff).session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM\",\"clientReqId\":\"fl-key\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/stocktakes/" + st + "/diffs").session(editor)
                        .param("confirmStatus", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/stocktakes/" + st + "/diffs").session(editor)
                        .param("confirmStatus", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].confirmStatus").value(1));
        // 差异不存在/跨单差异 → 404
        mockMvc.perform(get("/api/stocktakes/999999/diffs").session(editor))
                .andExpect(status().isNotFound());

        // 列表 status 筛选：唯一差异已处理（自动转已确认）→ 进行中 0 条
        mockMvc.perform(get("/api/stocktakes").session(editor).param("status", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }
}
