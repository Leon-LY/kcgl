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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.Map;
import java.util.StringJoiner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批量软删 / 批量恢复集成测试（D-126）：POST /api/items/recycle-delete、/recycle-restore。
 *
 * <p>被测的核心不是「批量」这个外壳，而是**逐件语义与单件完全一致**：每件各落一行流水
 * （各自 clientReqId 幂等），各记一条审计，各走一次版本守卫；批内某件失败不牵连其余件。
 *
 * <p>逐件而非整批回滚是有意设计（见 {@code RecycleBatchResponse} 类注释）：批内各件之间
 * 没有共同不变量，而列表页勾选后陈旧行几乎必然出现，整批回滚会让一行陈旧数据废掉整次操作。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class RecycleBatchIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String PASSWORD = "Batch-1234-x";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    LedgerConsistencyService consistencyService;

    long htVenueId;

    @BeforeEach
    void resetFixtures() {
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM stock_ledger");
        jdbcTemplate.update("DELETE FROM item");
        jdbcTemplate.update("DELETE FROM seq_item_code");
        jdbcTemplate.update("DELETE FROM sys_setting");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN ('boss','eichi','miru')");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('boss', ?, '管理者', 1, 1, 0), ('eichi', ?, '編集者', 2, 1, 0), ('miru', ?, '閲覧者', 3, 1, 0)
                """, ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD));
        jdbcTemplate.update("DELETE FROM auction_venue");
        jdbcTemplate.update("DELETE FROM price_band");
        jdbcTemplate.update(
                "INSERT INTO price_band(code, lower_bound, upper_bound, enabled) VALUES ('X', 0, 3000, 1)");
        jdbcTemplate.update(
                "INSERT INTO auction_venue(code, name, enabled) VALUES ('HT', '飛騨古民具市', 1)");
        htVenueId = jdbcTemplate.queryForObject(
                "SELECT id FROM auction_venue WHERE code = 'HT'", Long.class);
    }

    // ------------------------------------------------------------- 夹具与工具

    private MockHttpSession loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .param("username", username).param("password", PASSWORD))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("登录应成功: %s",
                result.getResponse().getContentAsString()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession();
    }

    private long createItem(MockHttpSession session, String clientReqId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/items").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"clientReqId\":\"" + clientReqId + "\",\"venueId\":" + htVenueId
                                + ",\"buyDate\":\"2026-09-15\",\"purchasePrice\":1000,\"warehouse\":1}")))
                .andExpect(status().isOk())
                .andReturn();
        return Long.parseLong(result.getResponse().getContentAsString()
                .replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    /** 到仓：在途→在库，使软删记账 qty=-1（否则在途件记 0，测不出入账口径）。 */
    private void arrive(MockHttpSession session, long id, String clientReqId) throws Exception {
        mockMvc.perform(post("/api/inventory/arrivals").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"items\":[{\"itemId\":" + id + ",\"clientReqId\":\"" + clientReqId
                                + "\",\"warehouse\":1}],\"warehouseInDate\":\"2026-09-01\"}")))
                .andExpect(status().isOk());
    }

    private void voidItem(MockHttpSession session, long id, String clientReqId) throws Exception {
        mockMvc.perform(post("/api/items/{id}/void", id).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"clientReqId\":\"" + clientReqId + "\",\"reason\":\"重録のため\"}")))
                .andExpect(status().isOk());
    }

    /** 建件并到仓，返回「已在库」的件 id。 */
    private long arrivedItem(MockHttpSession session, String seed) throws Exception {
        long id = createItem(session, "c-" + seed);
        arrive(session, id, "a-" + seed);
        return id;
    }

    private String batchBody(String reason, long[] ids, String[] keys) {
        StringJoiner items = new StringJoiner(",", "[", "]");
        for (int i = 0; i < ids.length; i++) {
            items.add("{\"id\":" + ids[i] + ",\"clientReqId\":\"" + keys[i] + "\"}");
        }
        return "{\"items\":" + items + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}";
    }

    private ResultActions postBatch(String path, MockHttpSession session, String body) throws Exception {
        return mockMvc.perform(post(path).session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String[] keys(String prefix, int count) {
        String[] out = new String[count];
        for (int i = 0; i < count; i++) {
            out[i] = prefix + "-" + i;
        }
        return out;
    }

    private int deletedFlag(long id) {
        return jdbcTemplate.queryForObject("SELECT deleted FROM item WHERE id = ?", Integer.class, id);
    }

    private int ledgerCount(String clientReqId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE client_req_id = ?", Integer.class, clientReqId);
    }

    private long auditCount(String action, long itemId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = ? AND entity_id = ?", Long.class,
                action, itemId);
    }

    // ------------------------------------------------------------- 批量软删

    @Test
    void deleteBatch_deletesEveryItem_recordsOneLedgerAndAuditPerItem() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long a = arrivedItem(boss, "b1");
        long b = arrivedItem(boss, "b2");
        long c = arrivedItem(boss, "b3");

        postBatch("/api/items/recycle-delete", boss,
                batchBody("重複分の整理", new long[] {a, b, c}, keys("d", 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeeded").value(3))
                .andExpect(jsonPath("$.data.failures").isEmpty());

        assertThat(deletedFlag(a)).isEqualTo(1);
        assertThat(deletedFlag(b)).isEqualTo(1);
        assertThat(deletedFlag(c)).isEqualTo(1);

        // 逐件：每件一条流水（各自键）、一条审计——批量不是「一条流水记 N 件」
        for (int i = 0; i < 3; i++) {
            assertThat(ledgerCount("d-" + i)).as("第 %d 件应有且仅有一条流水", i).isEqualTo(1);
        }
        Map<String, Object> ledger = jdbcTemplate.queryForMap(
                "SELECT txn_type, qty_change, wh_from FROM stock_ledger WHERE client_req_id = ?", "d-0");
        assertThat(((Number) ledger.get("qty_change")).intValue())
                .as("在库件软删记 -1，仓计入 wh_from").isEqualTo(-1);
        assertThat(ledger.get("wh_from")).isEqualTo(1);
        assertThat(auditCount("RECYCLE_DELETE", a)).isEqualTo(1);
        assertThat(auditCount("RECYCLE_DELETE", b)).isEqualTo(1);
        assertThat(auditCount("RECYCLE_DELETE", c)).isEqualTo(1);

        assertThat(consistencyService.check().ok()).as("批量软删后对账不变量仍成立").isTrue();
    }

    @Test
    void deleteBatch_reportsPerItemFailure_whileOtherItemsStillSucceed() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long a = arrivedItem(boss, "c1");
        long b = arrivedItem(boss, "c2");
        long c = arrivedItem(boss, "c3");

        // b 已被他人删掉：列表页那行是选中期间的陈旧数据
        mockMvc.perform(delete("/api/items/{id}", b).session(boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"pre-b\"}"))
                .andExpect(status().isOk());

        postBatch("/api/items/recycle-delete", boss,
                batchBody(null, new long[] {a, b, c}, keys("e", 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeeded").value(2))
                .andExpect(jsonPath("$.data.failures.length()").value(1))
                .andExpect(jsonPath("$.data.failures[0].itemId").value(b))
                .andExpect(jsonPath("$.data.failures[0].code").value(409014));

        // 关键：一行陈旧数据没有废掉整次操作
        assertThat(deletedFlag(a)).isEqualTo(1);
        assertThat(deletedFlag(c)).isEqualTo(1);
        assertThat(consistencyService.check().ok()).isTrue();
    }

    @Test
    void deleteBatch_reportsNotFound_forUnknownId() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long a = arrivedItem(boss, "f1");

        postBatch("/api/items/recycle-delete", boss,
                batchBody(null, new long[] {a, 999999L}, keys("g", 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeeded").value(1))
                .andExpect(jsonPath("$.data.failures[0].itemId").value(999999))
                .andExpect(jsonPath("$.data.failures[0].code").value(404001));
    }

    @Test
    void deleteBatch_replayedWithSameKeys_isIdempotent_noExtraLedger() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long a = arrivedItem(boss, "h1");
        long b = arrivedItem(boss, "h2");
        String body = batchBody(null, new long[] {a, b}, keys("i", 2));

        postBatch("/api/items/recycle-delete", boss, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeeded").value(2));
        // 重放同一请求（网络重试语义）：命中各件幂等键，读回原结果，不新增流水
        postBatch("/api/items/recycle-delete", boss, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeeded").value(2))
                .andExpect(jsonPath("$.data.failures").isEmpty());

        assertThat(ledgerCount("i-0")).as("重放不得补记流水").isEqualTo(1);
        assertThat(ledgerCount("i-1")).isEqualTo(1);
        assertThat(consistencyService.check().ok()).isTrue();
    }

    @Test
    void deleteBatch_recordsZeroQtyForVoidedInStockItem() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long a = arrivedItem(boss, "j1");
        voidItem(boss, a, "v-j1");

        postBatch("/api/items/recycle-delete", boss,
                batchBody(null, new long[] {a}, keys("k", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeeded").value(1));

        // 作废件 VOID 已记过 -1，软删再记即双重扣减，破坏 Σledger≡COUNT
        Map<String, Object> ledger = jdbcTemplate.queryForMap(
                "SELECT qty_change, wh_from FROM stock_ledger WHERE client_req_id = ?", "k-0");
        assertThat(((Number) ledger.get("qty_change")).intValue())
                .as("已作废在库件软删必须记 0").isZero();
        assertThat(ledger.get("wh_from")).isNull();
        assertThat(consistencyService.check().ok()).as("作废件软删不得双重扣减").isTrue();
    }

    @Test
    void deleteBatch_rejectsEmptyListAndOverLimit() throws Exception {
        MockHttpSession boss = loginAs("boss");
        postBatch("/api/items/recycle-delete", boss, batchBody(null, new long[] {}, new String[] {}))
                .andExpect(status().isBadRequest());

        // 上限 100：101 件即越界（一次批量的上限=一屏能勾选的上限）
        int n = 101;
        long[] ids = new long[n];
        for (int i = 0; i < n; i++) {
            ids[i] = i + 1L;
        }
        postBatch("/api/items/recycle-delete", boss, batchBody(null, ids, keys("m", n)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteBatch_isAdminOnly() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long a = arrivedItem(boss, "n1");
        String body = batchBody(null, new long[] {a}, keys("o", 1));

        for (String user : new String[] {"eichi", "miru"}) {
            postBatch("/api/items/recycle-delete", loginAs(user), body)
                    .andExpect(status().isForbidden());
        }
        assertThat(deletedFlag(a)).as("被拒的批量不得留下任何改动").isZero();
    }

    // ------------------------------------------------------------- 批量恢复

    @Test
    void restoreBatch_restoresEveryItem() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long a = arrivedItem(boss, "p1");
        long b = arrivedItem(boss, "p2");

        postBatch("/api/items/recycle-delete", boss,
                batchBody(null, new long[] {a, b}, keys("q", 2)))
                .andExpect(status().isOk());

        postBatch("/api/items/recycle-restore", boss,
                batchBody(null, new long[] {a, b}, keys("r", 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeeded").value(2))
                .andExpect(jsonPath("$.data.failures").isEmpty());

        assertThat(deletedFlag(a)).isZero();
        assertThat(deletedFlag(b)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM item WHERE id = ? AND deleted_at IS NULL AND deleted_by IS NULL",
                Integer.class, a)).as("恢复须清掉 deleted_at/deleted_by 两个痕迹").isEqualTo(1);
        assertThat(auditCount("RECYCLE_RESTORE", a)).isEqualTo(1);
        assertThat(consistencyService.check().ok()).as("批量恢复后对账不变量仍成立").isTrue();
    }

    @Test
    void restoreBatch_reportsNotDeleted_forLiveItem() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long a = arrivedItem(boss, "s1");
        long b = arrivedItem(boss, "s2");
        postBatch("/api/items/recycle-delete", boss,
                batchBody(null, new long[] {b}, keys("t", 1)))
                .andExpect(status().isOk());

        // a 从未被删：恢复它应报「未处于删除状态」而非静默成功
        postBatch("/api/items/recycle-restore", boss,
                batchBody(null, new long[] {a, b}, keys("u", 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeeded").value(1))
                .andExpect(jsonPath("$.data.failures[0].itemId").value(a))
                .andExpect(jsonPath("$.data.failures[0].code").value(409015));

        assertThat(deletedFlag(b)).isZero();
    }

    @Test
    void restoreBatch_isAdminOnly() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long a = arrivedItem(boss, "v1");
        postBatch("/api/items/recycle-delete", boss,
                batchBody(null, new long[] {a}, keys("w", 1)))
                .andExpect(status().isOk());

        for (String user : new String[] {"eichi", "miru"}) {
            postBatch("/api/items/recycle-restore", loginAs(user),
                    batchBody(null, new long[] {a}, keys("x-" + user, 1)))
                    .andExpect(status().isForbidden());
        }
        assertThat(deletedFlag(a)).as("被拒的批量恢复不得改动数据").isEqualTo(1);
    }
}
