package com.kcgl;

import com.kcgl.module.auth.KcglUserDetails;
import com.kcgl.module.inventory.ArrivalService;
import com.kcgl.module.inventory.InventoryActionService;
import com.kcgl.module.inventory.LedgerConsistencyService;
import com.kcgl.module.inventory.dto.ActionResult;
import com.kcgl.module.inventory.dto.ArrivalRequest;
import com.kcgl.module.inventory.dto.ArrivalResponse;
import com.kcgl.module.inventory.dto.SellRequest;
import com.kcgl.module.user.SysUserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 动作端点集成测试（M3-⑤，docs/01 7.2 边表展开）：卖出/报废/调拨/退货双向/上架标记。
 * 每动作断言五元组：响应现态快照 + item 行 + ledger 入账规则（M3-② 对账不变量的解读）+
 * 审计 + 幂等重放；非法态 409008 整回滚；三角色权限矩阵。对账自检穿插于各动作后——
 * 动作端点即不变量的实现，不变量成立是端点正确性的最强证明。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class InventoryActionIntegrationTest {

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
    @Autowired
    InventoryActionService actionService;
    @Autowired
    ArrivalService arrivalService;

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

    /** 录一件在途（预计仓库默认 1）并返回 id。 */
    private long createItem(MockHttpSession session, String clientReqId) throws Exception {
        String json = mockMvc.perform(post("/api/items").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"" + clientReqId + "\",\"venueId\":" + venueId
                                + ",\"buyDate\":\"2026-09-15\",\"purchasePrice\":1000,\"warehouse\":1,"
                                + "\"remark\":\"動作テスト\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    /** 到仓（在库化）；warehouseInDate 选填。 */
    private void arrive(MockHttpSession session, long itemId, String clientReqId, String warehouseInDate)
            throws Exception {
        mockMvc.perform(post("/api/inventory/arrivals").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"itemId\":" + itemId + ",\"clientReqId\":\"" + clientReqId
                                + "\"}]"
                                + (warehouseInDate == null ? "" : ",\"warehouseInDate\":\"" + warehouseInDate + "\"")
                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].stockStatus").value(1));
    }

    private String postAction(MockHttpSession session, String path, String body) throws Exception {
        return mockMvc.perform(post("/api/inventory/" + path).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
    }

    private Map<String, Object> ledger(long itemId, int txnType) {
        return jdbcTemplate.queryForMap("""
                SELECT stock_from, stock_to, sale_from, sale_to, wh_from, wh_to, qty_change,
                       reason, return_direction, client_req_id
                FROM stock_ledger WHERE item_id = ? AND txn_type = ?
                """, itemId, txnType);
    }

    private static int v(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).intValue();
    }

    // ------------------------------------------------------------- 卖出

    @Test
    void sell_inStock_writesLedgerAuditAndSnapshot() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long id = createItem(editor, "sell-a");
        arrive(editor, id, "sell-a-arr", null);

        String body = postAction(editor, "sell",
                "{\"itemId\":" + id + ",\"clientReqId\":\"sell-a-key\",\"soldPrice\":15000}");
        assertThat(body).contains("\"stockStatus\":2").contains("\"saleStatus\":2")
                .contains("\"warehouse\":1");

        Map<String, Object> item = jdbcTemplate.queryForMap(
                "SELECT stock_status, sale_status, sold_price, version FROM item WHERE id = ?", id);
        assertThat(v(item, "stock_status")).isEqualTo(2);
        assertThat(v(item, "sale_status")).isEqualTo(2);
        assertThat(((Number) item.get("sold_price")).longValue()).isEqualTo(15000);
        assertThat(v(item, "version")).isEqualTo(2); // 到仓+卖出各推进一次

        // SELL 行：在库件记 (仓,−1)；销售 0→2 落列
        Map<String, Object> row = ledger(id, 3);
        assertThat(v(row, "stock_from")).isEqualTo(1);
        assertThat(v(row, "stock_to")).isEqualTo(2);
        assertThat(v(row, "sale_from")).isEqualTo(0);
        assertThat(v(row, "sale_to")).isEqualTo(2);
        assertThat(v(row, "wh_from")).isEqualTo(1);
        assertThat(row.get("wh_to")).isNull();
        assertThat(v(row, "qty_change")).isEqualTo(-1);
        assertThat(row.get("client_req_id")).isEqualTo("sell-a-key");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'ITEM_SELL' AND entity_id = ?",
                Long.class, id)).isEqualTo(1);
        assertThat(consistency.check().ok()).as("卖出后账实一致").isTrue();
    }

    @Test
    void sell_idempotentReplay_sameBody_singleLedger_keyMisuse400() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long id = createItem(editor, "sell-rp");
        arrive(editor, id, "sell-rp-arr", null);
        long other = createItem(editor, "sell-rp-o");
        arrive(editor, other, "sell-rp-o-arr", null);

        String first = postAction(editor, "sell",
                "{\"itemId\":" + id + ",\"clientReqId\":\"sell-rp-key\",\"soldPrice\":5000}");
        String second = postAction(editor, "sell",
                "{\"itemId\":" + id + ",\"clientReqId\":\"sell-rp-key\",\"soldPrice\":5000}");
        assertThat(second).isEqualTo(first); // 重放=原结果 200 出清
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 3", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'ITEM_SELL'", Long.class)).isEqualTo(1);

        // 同键打到别的商品 → 400（防脏读，绝不静默返回错数据）
        mockMvc.perform(post("/api/inventory/sell").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + other + ",\"clientReqId\":\"sell-rp-key\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        // ARRIVAL 的键被 SELL 挪用 → 400（uk_client_req 全局唯一，漏检必 500）
        mockMvc.perform(post("/api/inventory/sell").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + other + ",\"clientReqId\":\"sell-rp-arr\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 3", Long.class)).isEqualTo(1);
    }

    @Test
    void sell_invalidStates_409008_zeroSideEffects() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long transit = createItem(editor, "sell-tr");
        // 在途件不可卖
        mockMvc.perform(post("/api/inventory/sell").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + transit + ",\"clientReqId\":\"sell-tr-key\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409008));
        // 已出库件再卖 → 409008
        long id = createItem(editor, "sell-tw");
        arrive(editor, id, "sell-tw-arr", null);
        postAction(editor, "sell", "{\"itemId\":" + id + ",\"clientReqId\":\"sell-tw-1\"}");
        mockMvc.perform(post("/api/inventory/sell").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + id + ",\"clientReqId\":\"sell-tw-2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409008));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 3", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_status FROM item WHERE id = ?", Integer.class, transit)).isZero();
    }

    @Test
    void sell_soldPriceBounds_400() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long id = createItem(editor, "sell-pr");
        for (String price : new String[]{"0", "-5", "100000000"}) {
            mockMvc.perform(post("/api/inventory/sell").session(editor)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"itemId\":" + id + ",\"clientReqId\":\"sell-pr-"
                                    + price.replace("-", "m") + "\",\"soldPrice\":" + price + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400001));
        }
        // 不带 soldPrice 合法（雅虎 CSV 回填场景）
        arrive(editor, id, "sell-pr-arr", null);
        mockMvc.perform(post("/api/inventory/sell").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + id + ",\"clientReqId\":\"sell-pr-ok\"}"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sold_price FROM item WHERE id = ?", Long.class, id)).isNull();
    }

    // ------------------------------------------------------------- 并发同键双发（7.0 契约）

    /** 服务层直调（审计读 SecurityContext）：并发测试在池线程里显式装填身份。 */
    private static void runAs(Long userId, String username) {
        SysUserEntity user = new SysUserEntity();
        user.setId(userId);
        user.setUsername(username);
        user.setPasswordHash("x");
        user.setDisplayName(username);
        user.setRole(2);
        user.setEnabled(1);
        user.setMustChangePwd(0);
        user.setLocale("ja-JP");
        KcglUserDetails details = KcglUserDetails.of(user, Clock.systemDefaultZone());
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())));
    }

    private long operatorId(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    @Test
    void sell_concurrentSameKey_bothReadBack_singleLedger() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long id = createItem(editor, "sell-cc");
        arrive(editor, id, "sell-cc-arr", null);
        long operatorId = operatorId("eichi");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<ActionResult>> futures = new ArrayList<>();
            for (int t = 0; t < 2; t++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    runAs(operatorId, "eichi");
                    return actionService.sell(new SellRequest(id, "sell-cc-key", 8000L),
                            operatorId, "編集者");
                }));
            }
            gate.countDown();
            // 7.0：并发同键双发（连点/双端重试）→ 两端均 200 读回出清，绝不 409
            for (Future<ActionResult> future : futures) {
                ActionResult result = future.get(30, TimeUnit.SECONDS);
                assertThat(result.stockStatus()).isEqualTo(2);
                assertThat(result.saleStatus()).isEqualTo(2);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 3", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'ITEM_SELL'", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sold_price FROM item WHERE id = ?", Long.class, id)).isEqualTo(8000L);
        assertThat(consistency.check().ok()).as("并发卖出后账实一致").isTrue();
    }

    @Test
    void arrive_concurrentSameKeyBatch_bothReadBack_singleLedger() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long id = createItem(editor, "arr-cc");
        long operatorId = operatorId("eichi");
        ArrivalRequest request = new ArrivalRequest(
                List.of(new ArrivalRequest.ArrivalLine(id, "arr-cc-key", null, null)), null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<ArrivalResponse>> futures = new ArrayList<>();
            for (int t = 0; t < 2; t++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    runAs(operatorId, "eichi");
                    return arrivalService.arrive(request, operatorId, "編集者");
                }));
            }
            gate.countDown();
            for (Future<ArrivalResponse> future : futures) {
                ArrivalResponse response = future.get(30, TimeUnit.SECONDS);
                assertThat(response.arrivedCount()).isEqualTo(1);
                assertThat(response.items().get(0).stockStatus()).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 2", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'ITEM_ARRIVAL'", Long.class)).isEqualTo(1);
        assertThat(consistency.check().ok()).as("并发到仓后账实一致").isTrue();
    }

    // ------------------------------------------------------------- 报废

    @Test
    void scrap_inStock_minusOne_reasonRequired() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long id = createItem(editor, "scr-a");
        arrive(editor, id, "scr-a-arr", null);

        String body = postAction(editor, "scrap",
                "{\"itemId\":" + id + ",\"clientReqId\":\"scr-a-key\",\"reason\":\"破損のため廃棄\"}");
        assertThat(body).contains("\"stockStatus\":2").contains("\"saleStatus\":3");

        Map<String, Object> row = ledger(id, 4);
        assertThat(v(row, "stock_from")).isEqualTo(1);
        assertThat(v(row, "stock_to")).isEqualTo(2);
        assertThat(v(row, "sale_from")).isEqualTo(0);
        assertThat(v(row, "sale_to")).isEqualTo(3);
        assertThat(v(row, "wh_from")).isEqualTo(1);
        assertThat(row.get("wh_to")).isNull();
        assertThat(v(row, "qty_change")).isEqualTo(-1);
        assertThat(row.get("reason")).isEqualTo("破損のため廃棄");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'ITEM_SCRAP'", Long.class)).isEqualTo(1);
        assertThat(consistency.check().ok()).as("报废后账实一致").isTrue();

        // 在途件报废 → 409008
        long transit = createItem(editor, "scr-tr");
        mockMvc.perform(post("/api/inventory/scrap").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + transit + ",\"clientReqId\":\"scr-tr-key\","
                                + "\"reason\":\"廃棄\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409008));
    }

    // ------------------------------------------------------------- 调拨

    @Test
    void transfer_ledgerBothSides_andValidations() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long id = createItem(editor, "tr-a");
        arrive(editor, id, "tr-a-arr", null);

        String body = postAction(editor, "transfer",
                "{\"itemId\":" + id + ",\"clientReqId\":\"tr-a-key\",\"toWarehouse\":2}");
        assertThat(body).contains("\"warehouse\":2").contains("\"stockStatus\":1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT warehouse FROM item WHERE id = ?", Integer.class, id)).isEqualTo(2);

        // TRANSFER 行：双侧入账 (1,−1)(2,+1) qty=0，stock 1→1
        Map<String, Object> row = ledger(id, 5);
        assertThat(v(row, "stock_from")).isEqualTo(1);
        assertThat(v(row, "stock_to")).isEqualTo(1);
        assertThat(row.get("sale_from")).isNull();
        assertThat(v(row, "wh_from")).isEqualTo(1);
        assertThat(v(row, "wh_to")).isEqualTo(2);
        assertThat(v(row, "qty_change")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'ITEM_TRANSFER'", Long.class)).isEqualTo(1);
        assertThat(consistency.check().ok()).as("调拨后账实一致（头寸随仓迁移）").isTrue();

        // 同仓调拨 → 400；非法仓号 → 400；在途件调拨 → 409008
        mockMvc.perform(post("/api/inventory/transfer").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + id + ",\"clientReqId\":\"tr-same\",\"toWarehouse\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        mockMvc.perform(post("/api/inventory/transfer").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + id + ",\"clientReqId\":\"tr-bad\",\"toWarehouse\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        long transit = createItem(editor, "tr-tr");
        mockMvc.perform(post("/api/inventory/transfer").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + transit + ",\"clientReqId\":\"tr-tr-key\",\"toWarehouse\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409008));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 5", Long.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------- 退货

    @Test
    void returnCustomer_afterSell_backInStock_keepsWarehouseInDate() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long id = createItem(editor, "rc-a");
        arrive(editor, id, "rc-a-arr", "2026-09-01");
        postAction(editor, "sell", "{\"itemId\":" + id + ",\"clientReqId\":\"rc-a-sell\"}");

        String body = postAction(editor, "return",
                "{\"itemId\":" + id + ",\"clientReqId\":\"rc-a-key\",\"direction\":1,"
                        + "\"note\":\"お客様都合で返品\"}");
        assertThat(body).contains("\"stockStatus\":1").contains("\"saleStatus\":3");

        // RETURN 行（顾客退回）：回原仓记 (仓,+1)；成交→取消；入库日保留（D-045）
        Map<String, Object> row = ledger(id, 6);
        assertThat(v(row, "stock_from")).isEqualTo(2);
        assertThat(v(row, "stock_to")).isEqualTo(1);
        assertThat(v(row, "sale_from")).isEqualTo(2);
        assertThat(v(row, "sale_to")).isEqualTo(3);
        assertThat(row.get("wh_from")).isNull();
        assertThat(v(row, "wh_to")).isEqualTo(1);
        assertThat(v(row, "qty_change")).isEqualTo(1);
        assertThat(v(row, "return_direction")).isEqualTo(1);
        assertThat(row.get("reason")).isEqualTo("お客様都合で返品");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT warehouse_in_date FROM item WHERE id = ?", java.time.LocalDate.class, id))
                .isEqualTo(java.time.LocalDate.of(2026, 9, 1));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'ITEM_RETURN_CUSTOMER'",
                Long.class)).isEqualTo(1);
        assertThat(consistency.check().ok()).as("顾客退回后账实一致").isTrue();
    }

    @Test
    void returnCustomer_requiresSoldSaleState_409008() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long id = createItem(editor, "rc-b");
        arrive(editor, id, "rc-b-arr", null);
        postAction(editor, "scrap", "{\"itemId\":" + id + ",\"clientReqId\":\"rc-b-scrap\","
                + "\"reason\":\"廃棄\"}");
        // 报废件销售态=取消：顾客退回边只认成交态
        mockMvc.perform(post("/api/inventory/return").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + id + ",\"clientReqId\":\"rc-b-key\",\"direction\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409008));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 6", Long.class)).isZero();
    }

    @Test
    void returnVenue_inStockAndTransit_ledgerSemantics() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long inStockId = createItem(editor, "rv-a");
        arrive(editor, inStockId, "rv-a-arr", null);
        long transitId = createItem(editor, "rv-b");

        postAction(editor, "return",
                "{\"itemId\":" + inStockId + ",\"clientReqId\":\"rv-a-key\",\"direction\":2}");
        postAction(editor, "return",
                "{\"itemId\":" + transitId + ",\"clientReqId\":\"rv-b-key\",\"direction\":2}");

        // 在库件退回：记 (仓,−1)
        Map<String, Object> inStockRow = ledger(inStockId, 6);
        assertThat(v(inStockRow, "stock_from")).isEqualTo(1);
        assertThat(v(inStockRow, "stock_to")).isEqualTo(2);
        assertThat(v(inStockRow, "wh_from")).isEqualTo(1);
        assertThat(inStockRow.get("wh_to")).isNull();
        assertThat(v(inStockRow, "qty_change")).isEqualTo(-1);
        assertThat(v(inStockRow, "return_direction")).isEqualTo(2);
        // 在途件退回：从未入账，不占仓账（wh 全 NULL qty=0）
        Map<String, Object> transitRow = ledger(transitId, 6);
        assertThat(v(transitRow, "stock_from")).isZero();
        assertThat(v(transitRow, "stock_to")).isEqualTo(2);
        assertThat(transitRow.get("wh_from")).isNull();
        assertThat(transitRow.get("wh_to")).isNull();
        assertThat(v(transitRow, "qty_change")).isZero();
        assertThat(v(transitRow, "return_direction")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'ITEM_RETURN_VENUE'",
                Long.class)).isEqualTo(2);
        assertThat(consistency.check().ok()).as("退回拍卖场后账实一致（两态入账规则不同）").isTrue();
    }

    @Test
    void return_invalidDirection_400() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long id = createItem(editor, "rv-c");
        arrive(editor, id, "rv-c-arr", null);
        mockMvc.perform(post("/api/inventory/return").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + id + ",\"clientReqId\":\"rv-c-key\",\"direction\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 6", Long.class)).isZero();
    }

    // ------------------------------------------------------------- 上架标记

    @Test
    void markListed_inStockNotListed_toOnSale() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long id = createItem(editor, "ml-a");
        arrive(editor, id, "ml-a-arr", null);

        String body = postAction(editor, "mark-listed",
                "{\"itemId\":" + id + ",\"clientReqId\":\"ml-a-key\"}");
        assertThat(body).contains("\"saleStatus\":1").contains("\"stockStatus\":1");

        // LIST_UP 行：实物未动（wh 全 NULL qty=0），销售 0→1
        Map<String, Object> row = ledger(id, 9);
        assertThat(v(row, "stock_from")).isEqualTo(1);
        assertThat(v(row, "stock_to")).isEqualTo(1);
        assertThat(v(row, "sale_from")).isZero();
        assertThat(v(row, "sale_to")).isEqualTo(1);
        assertThat(row.get("wh_from")).isNull();
        assertThat(row.get("wh_to")).isNull();
        assertThat(v(row, "qty_change")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'ITEM_LIST_UP'", Long.class)).isEqualTo(1);
        assertThat(consistency.check().ok()).as("上架标记后账实一致（实物未动）").isTrue();

        // 已在售再标记 → 409008（边 0→1 只认未上架）；在途件标记 → 409008
        mockMvc.perform(post("/api/inventory/mark-listed").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + id + ",\"clientReqId\":\"ml-a-again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409008));
        long transit = createItem(editor, "ml-b");
        mockMvc.perform(post("/api/inventory/mark-listed").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + transit + ",\"clientReqId\":\"ml-b-key\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409008));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 9", Long.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------- 权限与冻结守卫

    @Test
    void actions_byViewer403_onVoided409006_onDeleted404() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        MockHttpSession viewer = loginAs("miru");
        long id = createItem(editor, "guard-a");
        arrive(editor, id, "guard-a-arr", null);
        long voidedId = createItem(editor, "guard-b");
        arrive(editor, voidedId, "guard-b-arr", null);
        mockMvc.perform(post("/api/items/" + voidedId + "/void").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"guard-void\",\"reason\":\"誤入力\"}"))
                .andExpect(status().isOk());
        long deletedId = createItem(editor, "guard-c");
        jdbcTemplate.update("UPDATE item SET deleted = 1 WHERE id = ?", deletedId);

        // 仅查看角色：五端点全 403 零副作用
        for (String path : new String[]{"sell", "scrap", "transfer", "return", "mark-listed"}) {
            String body = "{\"itemId\":" + id + ",\"clientReqId\":\"guard-v-" + path + "\""
                    + ("scrap".equals(path) ? ",\"reason\":\"廃棄\"" : "")
                    + ("transfer".equals(path) ? ",\"toWarehouse\":2" : "")
                    + ("return".equals(path) ? ",\"direction\":1" : "")
                    + "}";
            mockMvc.perform(post("/api/inventory/" + path).session(viewer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
        }
        // 作废=冻结禁一切迁移；软删件按不存在
        mockMvc.perform(post("/api/inventory/sell").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + voidedId + ",\"clientReqId\":\"guard-vd\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409006));
        mockMvc.perform(post("/api/inventory/sell").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + deletedId + ",\"clientReqId\":\"guard-dl\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404001));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type IN (3,4,5,6,9)", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_status FROM item WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    // ------------------------------------------------------------- 动作后 by-code 可定位

    @Test
    void byCode_locatesItemAfterActions_normalizesCaseAndFullWidth() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long id = createItem(editor, "bc-a");
        arrive(editor, id, "bc-a-arr", null);
        postAction(editor, "transfer",
                "{\"itemId\":" + id + ",\"clientReqId\":\"bc-a-tr\",\"toWarehouse\":2}");

        String code = jdbcTemplate.queryForObject(
                "SELECT item_code FROM item WHERE id = ?", String.class, id);
        // 小写手输 → 大文字化命中
        mockMvc.perform(get("/api/items/by-code/" + code.toLowerCase(java.util.Locale.ROOT))
                        .session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.id").value(id))
                .andExpect(jsonPath("$.data.item.warehouse").value(2))
                .andExpect(jsonPath("$.data.item.stockStatus").value(1))
                .andExpect(jsonPath("$.data.reEntry").doesNotExist());
        // 全角手输 → NFKC 归一命中（路径段手工百分号编码后以 URI 直传——
        // MockMvc 的 urlTemplate 重载会再编码一次，双重编码必不命中）
        String encoded = java.net.URLEncoder.encode(toFullWidth(code),
                java.nio.charset.StandardCharsets.UTF_8);
        mockMvc.perform(get(java.net.URI.create("/api/items/by-code/" + encoded)).session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.id").value(id));
        // 不存在的号 → 404
        mockMvc.perform(get("/api/items/by-code/ZZ9-Z9Z").session(editor))
                .andExpect(status().isNotFound());
    }

    /** ASCII → 全角（数字/大写字母/连字符），模拟日本 IME 全角手输。 */
    private String toFullWidth(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') {
                sb.append((char) ('０' + (c - '0')));
            } else if (c >= 'A' && c <= 'Z') {
                sb.append((char) ('Ａ' + (c - 'A')));
            } else if (c == '-') {
                sb.append('－');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
