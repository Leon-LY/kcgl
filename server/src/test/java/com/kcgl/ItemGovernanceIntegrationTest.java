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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品治理集成测试（M5-①，D-063/D-064）：
 * - PUT 编辑：自由字段改/缺省清空（补 D-035「重录无法清空费用」缺口）、改价档位重推导
 *   而号内快照冻结、在库改仓 409013、乐观锁 409、校验镜像 create、作废件 409008
 * - 子资源：ledgers（时间倒序）/yahoo-listings（listed_at 倒序）全员可读
 * - 回收站：软删/恢复 clientReqId 幂等读回（D-045 C 全局键分类）、
 *   记账镜像 VOID（qty=(在库且未作废)?∓1:0——已作废在库件删/恢复必须 0，
 *   否则双重扣减破坏 Σledger≡COUNT 不变量）、对账自检 ok、A-only
 *
 * <p>夹具走真实端点链（create→arrive→sell/void），治理动作的被测对象是状态流转本身。
 * helper 命名 putItem：成员方法名 put 会收窄简单名解析到本类（JLS 15.12.1），
 * 遮蔽 MockMvcRequestBuilders.put 的静态导入。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class ItemGovernanceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String PASSWORD = "Gov-1234-x";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    LedgerConsistencyService consistencyService;

    long bossId;
    long htVenueId;
    long nrVenueId;

    @BeforeEach
    void resetFixtures() {
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM yahoo_listing");
        jdbcTemplate.update("DELETE FROM stock_ledger");
        jdbcTemplate.update("DELETE FROM item");
        jdbcTemplate.update("DELETE FROM seq_item_code");
        jdbcTemplate.update("DELETE FROM sys_setting");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN ('boss','eichi','miru')");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('boss', ?, '管理者', 1, 1, 0), ('eichi', ?, '編集者', 2, 1, 0), ('miru', ?, '閲覧者', 3, 1, 0)
                """, ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD));
        bossId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = 'boss'", Long.class);
        jdbcTemplate.update("DELETE FROM auction_venue");
        jdbcTemplate.update("DELETE FROM price_band");
        jdbcTemplate.update("DELETE FROM year_code");
        jdbcTemplate.update("INSERT INTO year_code(`year`, code) VALUES (2016,'A'),(2026,'K')");
        jdbcTemplate.update(
                "INSERT INTO price_band(code, lower_bound, upper_bound, enabled) VALUES ('X', 0, 3000, 1), ('Y', 3000, 1000000, 1)");
        jdbcTemplate.update(
                "INSERT INTO auction_venue(code, name, enabled) VALUES ('HT', '飛騨古民具市', 1), ('NR', '名古屋リサイクル市', 1)");
        htVenueId = jdbcTemplate.queryForObject("SELECT id FROM auction_venue WHERE code = 'HT'", Long.class);
        nrVenueId = jdbcTemplate.queryForObject("SELECT id FROM auction_venue WHERE code = 'NR'", Long.class);
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

    /** 真实录入一件（HT 2026-09-15 桶 → HTK9-A1X），返回 id。extras 形如 ",\"fee\":300,\"remark\":\"初稿\""。 */
    private long createItem(MockHttpSession session, String clientReqId, long venueId,
            long price, int warehouse, String extras) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/items").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"clientReqId\":\"" + clientReqId + "\",\"venueId\":" + venueId
                                + ",\"buyDate\":\"2026-09-15\",\"purchasePrice\":" + price
                                + ",\"warehouse\":" + warehouse + extras + "}")))
                .andExpect(status().isOk())
                .andReturn();
        return Long.parseLong(result.getResponse().getContentAsString()
                .replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    /** PUT 全量编辑体（D-063 必填五件 + extras 可选字段；extras 缺省=null 即清空）。 */
    private String editBody(int version, long venueId, String buyDate, long price, int warehouse, String extras) {
        return "{\"version\":" + version + ",\"venueId\":" + venueId + ",\"buyDate\":\"" + buyDate
                + "\",\"purchasePrice\":" + price + ",\"warehouse\":" + warehouse + extras + "}";
    }

    private ResultActions putItem(MockHttpSession session, long id, String body) throws Exception {
        return mockMvc.perform(put("/api/items/{id}", id).session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void arrive(MockHttpSession session, long id, String clientReqId, int warehouse) throws Exception {
        mockMvc.perform(post("/api/inventory/arrivals").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"items\":[{\"itemId\":" + id + ",\"clientReqId\":\"" + clientReqId
                                + "\",\"warehouse\":" + warehouse + "}],\"warehouseInDate\":\"2026-09-01\"}")))
                .andExpect(status().isOk());
    }

    private void sell(MockHttpSession session, long id, String clientReqId, long soldPrice) throws Exception {
        mockMvc.perform(post("/api/inventory/sell").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"itemId\":" + id + ",\"clientReqId\":\"" + clientReqId
                                + "\",\"soldPrice\":" + soldPrice + "}")))
                .andExpect(status().isOk());
    }

    private void voidItem(MockHttpSession session, long id, String clientReqId, String reason) throws Exception {
        mockMvc.perform(post("/api/items/{id}/void", id).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"clientReqId\":\"" + clientReqId + "\",\"reason\":\"" + reason + "\"}")))
                .andExpect(status().isOk());
    }

    private void recycleDelete(MockHttpSession session, long id, String clientReqId, String reason)
            throws Exception {
        String body = reason == null
                ? "{\"clientReqId\":\"" + clientReqId + "\"}"
                : "{\"clientReqId\":\"" + clientReqId + "\",\"reason\":\"" + reason + "\"}";
        mockMvc.perform(delete("/api/items/{id}", id).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private void restore(MockHttpSession session, long id, String clientReqId) throws Exception {
        mockMvc.perform(post("/api/items/{id}/restore", id).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"" + clientReqId + "\"}"))
                .andExpect(status().isOk());
    }

    private int versionOf(long id) {
        return jdbcTemplate.queryForObject("SELECT version FROM item WHERE id = ?", Integer.class, id);
    }

    /** 断言哪张表清哪张表（D-031 教训）：流水行按 client_req_id 取。数值统一 Number 取值（D-035 教训）。 */
    private Map<String, Object> ledgerOf(String clientReqId) {
        return jdbcTemplate.queryForMap(
                "SELECT txn_type, wh_from, wh_to, qty_change, stock_from, stock_to, reason "
                        + "FROM stock_ledger WHERE client_req_id = ?", clientReqId);
    }

    private long num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    // ------------------------------------------------------------- PUT 编辑（D-063）

    @Test
    void editFreeFields_updatesAndClearsWhenAbsent_recordsAuditDiff() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "c-1", htVenueId, 1000, 1, ",\"fee\":300,\"remark\":\"初稿\"");

        // 第一轮：设值
        putItem(boss, id, editBody(versionOf(id), htVenueId, "2026-09-15", 1000, 1,
                ",\"shelfNo\":\"S-1\",\"photoDate\":\"2026-09-20\",\"fee\":500,\"remark\":\"改稿\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shelfNo").value("S-1"))
                .andExpect(jsonPath("$.data.fee").value(500))
                .andExpect(jsonPath("$.data.itemCode").value("HTK9-A1X"));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT shelf_no, photo_date, fee, remark FROM item WHERE id = ?", id);
        assertThat(row.get("shelf_no")).isEqualTo("S-1");
        assertThat(row.get("photo_date").toString()).isEqualTo("2026-09-20");
        assertThat(((Number) row.get("fee")).longValue()).isEqualTo(500);

        // 第二轮：可选字段缺省=清空（D-063 1，补重录无法清空的缺口）
        putItem(boss, id, editBody(versionOf(id), htVenueId, "2026-09-15", 1000, 1, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fee").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.shelfNo").value(org.hamcrest.Matchers.nullValue()));
        row = jdbcTemplate.queryForMap("SELECT fee, shelf_no, remark, photo_date FROM item WHERE id = ?", id);
        assertThat(row.get("fee")).isNull();
        assertThat(row.get("shelf_no")).isNull();
        assertThat(row.get("remark")).isNull();
        assertThat(row.get("photo_date")).isNull();

        // 审计：ITEM_UPDATE 前后值 diff（JSON 列回读带空白，正则容错——D-060 F）
        String detail = jdbcTemplate.queryForObject(
                "SELECT detail FROM operation_log WHERE action = 'ITEM_UPDATE' ORDER BY id LIMIT 1",
                String.class);
        assertThat(detail).containsPattern("\"before\"\\s*:");
        assertThat(detail).containsPattern("\"after\"\\s*:");
        assertThat(detail).contains("300");
    }

    @Test
    void editPrice_rederivesBandWhileCodeAndSnapshotsFrozen() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "c-1", htVenueId, 1000, 1, "");

        putItem(boss, id, editBody(versionOf(id), htVenueId, "2026-09-15", 5000, 1, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.priceBandCode").value("Y"))
                .andExpect(jsonPath("$.data.itemCode").value("HTK9-A1X"));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT item_code, price_band_code, venue_code, year_code, buy_month FROM item WHERE id = ?", id);
        assertThat(row.get("item_code")).isEqualTo("HTK9-A1X");
        assertThat(row.get("price_band_code")).isEqualTo("Y");
        assertThat(row.get("venue_code")).isEqualTo("HT");
        assertThat(row.get("year_code")).isEqualTo("K");
        assertThat(((Number) row.get("buy_month")).intValue()).isEqualTo(9);

        // 新价无匹配档位 → 404002（与录入同口径，引导后台配置）
        putItem(boss, id, editBody(versionOf(id), htVenueId, "2026-09-15", 2000000, 1, ""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404002));
    }

    @Test
    void editVenueAndBuyDate_updatesColumnsSnapshotsStayFrozen() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "c-1", htVenueId, 1000, 1, "");

        putItem(boss, id, editBody(versionOf(id), nrVenueId, "2026-08-20", 1000, 1, ""))
                .andExpect(status().isOk());
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT venue_id, buy_date, venue_code, year_code, buy_month, item_code FROM item WHERE id = ?", id);
        assertThat(((Number) row.get("venue_id")).longValue()).isEqualTo(nrVenueId);
        assertThat(row.get("buy_date").toString()).isEqualTo("2026-08-20");
        // 号内快照段冻结（D-001）：号与快照列不动
        assertThat(row.get("item_code")).isEqualTo("HTK9-A1X");
        assertThat(row.get("venue_code")).isEqualTo("HT");
        assertThat(row.get("year_code")).isEqualTo("K");
        assertThat(((Number) row.get("buy_month")).intValue()).isEqualTo(9);
    }

    @Test
    void editWarehouse_transitAllowed_inStockAndShippedRejected() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "c-1", htVenueId, 1000, 1, "");

        // 在途改预计仓库：允许
        putItem(boss, id, editBody(versionOf(id), htVenueId, "2026-09-15", 1000, 2, ""))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT warehouse FROM item WHERE id = ?", Integer.class, id)).isEqualTo(2);

        // 在库改仓值：409013（改仓走 /inventory/transfer 台账路径）
        arrive(boss, id, "arr-1", 1);
        putItem(boss, id, editBody(versionOf(id), htVenueId, "2026-09-15", 1000, 2, ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409013));

        // 在库同值不视为改仓（契约 W，D-066）：A16 事后补录费用仍可编辑
        putItem(boss, id, editBody(versionOf(id), htVenueId, "2026-09-15", 1000, 1, ",\"fee\":500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fee").value(500));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT fee FROM item WHERE id = ?", Integer.class, id)).isEqualTo(500);

        // 已出库改仓值：同 409013
        sell(boss, id, "sell-1", 3000);
        putItem(boss, id, editBody(versionOf(id), htVenueId, "2026-09-15", 1000, 2, ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409013));
    }

    @Test
    void editVersionConflict_returns409() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "c-1", htVenueId, 1000, 1, "");

        putItem(boss, id, editBody(0, htVenueId, "2026-09-15", 1000, 1, ",\"fee\":500"))
                .andExpect(status().isOk());
        // 响应丢失重试场景：带旧 version 再提交 → 409 → 前端重读发现值已到位
        putItem(boss, id, editBody(0, htVenueId, "2026-09-15", 1000, 1, ",\"fee\":500"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409000));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT fee FROM item WHERE id = ?", Integer.class, id)).isEqualTo(500);
    }

    @Test
    void editRoles_editorOkViewerForbidden_voidedItemRejected() throws Exception {
        MockHttpSession boss = loginAs("boss");
        MockHttpSession eichi = loginAs("eichi");
        MockHttpSession miru = loginAs("miru");
        long id = createItem(boss, "c-1", htVenueId, 1000, 1, "");

        putItem(miru, id, editBody(0, htVenueId, "2026-09-15", 1000, 1, ""))
                .andExpect(status().isForbidden());
        putItem(eichi, id, editBody(0, htVenueId, "2026-09-15", 1000, 1, ""))
                .andExpect(status().isOk());

        // 作废件禁改（治理路径独占，409008）
        voidItem(boss, id, "v-1", "間違い");
        putItem(eichi, id, editBody(versionOf(id), htVenueId, "2026-09-15", 1000, 1, ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409008));
    }

    @Test
    void editValidations_mirrorCreateRules() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "c-1", htVenueId, 1000, 1, "");
        int v = versionOf(id);

        putItem(boss, id, editBody(v, htVenueId, "2030-01-01", 1000, 1, ""))
                .andExpect(status().isBadRequest());
        putItem(boss, id, editBody(v, htVenueId, "2026-09-15", 1000, 1, ",\"photoDate\":\"2030-01-01\""))
                .andExpect(status().isBadRequest());
        putItem(boss, id, editBody(v, 999999L, "2026-09-15", 1000, 1, ""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404003));
        putItem(boss, id, editBody(v, htVenueId, "2026-09-15", 100000000, 1, ""))
                .andExpect(status().isBadRequest());
        putItem(boss, id, editBody(v, htVenueId, "2026-09-15", 1000, 3, ""))
                .andExpect(status().isBadRequest());
        // 缺 purchasePrice → 400（构造合法 JSON，仅缺字段——非法 JSON 测的是解析器不是校验器）
        mockMvc.perform(put("/api/items/{id}", id).session(boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"version\":" + v + ",\"venueId\":" + htVenueId
                                + ",\"buyDate\":\"2026-09-15\",\"warehouse\":1}")))
                .andExpect(status().isBadRequest());
        // 入库日允许未来（预期到货日）
        putItem(boss, id, editBody(v, htVenueId, "2026-09-15", 1000, 1, ",\"warehouseInDate\":\"2030-01-01\""))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------- 子资源（D-061/D-064）

    @Test
    void itemLedgers_descendingByTime_allRolesCanRead() throws Exception {
        MockHttpSession boss = loginAs("boss");
        MockHttpSession miru = loginAs("miru");
        long id = createItem(boss, "c-1", htVenueId, 1000, 1, "");
        arrive(boss, id, "arr-1", 1);
        sell(boss, id, "sell-1", 3000);

        mockMvc.perform(get("/api/items/{id}/ledgers", id).session(boss))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(3))
                .andExpect(jsonPath("$.data.rows[0].txnType").value(3))
                .andExpect(jsonPath("$.data.rows[0].qtyChange").value(-1))
                .andExpect(jsonPath("$.data.rows[1].txnType").value(2))
                .andExpect(jsonPath("$.data.rows[2].txnType").value(1));
        mockMvc.perform(get("/api/items/{id}/ledgers", id).session(miru))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/items/999999/ledgers").session(boss))
                .andExpect(status().isNotFound());
    }

    @Test
    void itemYahooListings_descendingByListedAt_allRolesCanRead() throws Exception {
        MockHttpSession boss = loginAs("boss");
        MockHttpSession miru = loginAs("miru");
        long id = createItem(boss, "c-1", htVenueId, 1000, 1, "");
        jdbcTemplate.update("""
                INSERT INTO yahoo_listing(yahoo_auction_id, item_id, raw_item_code, item_code,
                  list_price, sold_price, status, listed_at, closed_at)
                VALUES ('auc-1', ?, 'HTK9-A1X', 'HTK9-A1X', 3000, NULL, 1, '2026-09-01 10:00:00', NULL)
                """, id);
        jdbcTemplate.update("""
                INSERT INTO yahoo_listing(yahoo_auction_id, item_id, raw_item_code, item_code,
                  list_price, sold_price, status, listed_at, closed_at)
                VALUES ('auc-2', ?, 'HTK9-A1X', 'HTK9-A1X', 4000, 4500, 2, '2026-09-10 10:00:00', '2026-09-15 10:00:00')
                """, id);

        mockMvc.perform(get("/api/items/{id}/yahoo-listings", id).session(boss))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.rows[0].yahooAuctionId").value("auc-2"))
                .andExpect(jsonPath("$.data.rows[0].soldPrice").value(4500))
                .andExpect(jsonPath("$.data.rows[1].yahooAuctionId").value("auc-1"));
        mockMvc.perform(get("/api/items/{id}/yahoo-listings", id).session(miru))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------- 回收站（D-064）

    @Test
    void recycleDeleteRestore_inStock_ledgerMirrorsVoidAndInvariantHolds() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "c-1", htVenueId, 1000, 1, "");
        arrive(boss, id, "arr-1", 1);
        String code = jdbcTemplate.queryForObject(
                "SELECT item_code FROM item WHERE id = ?", String.class, id);

        recycleDelete(boss, id, "del-1", "テスト整理");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT deleted, deleted_by, stock_status FROM item WHERE id = ?", id);
        assertThat(((Number) row.get("deleted")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("deleted_by")).longValue()).isEqualTo(bossId);
        assertThat(((Number) row.get("stock_status")).intValue()).isEqualTo(1);

        Map<String, Object> ledger = ledgerOf("del-1");
        assertThat(num(ledger, "txn_type")).isEqualTo(13);
        assertThat(num(ledger, "wh_from")).isEqualTo(1);
        assertThat(ledger.get("wh_to")).isNull();
        assertThat(num(ledger, "qty_change")).isEqualTo(-1);
        assertThat(num(ledger, "stock_from")).isEqualTo(1);
        assertThat(ledger.get("stock_to")).isNull();
        assertThat(ledger.get("reason")).isEqualTo("テスト整理");
        assertThat(consistencyService.check().ok()).as("软删后对账不变量仍成立").isTrue();

        // 软删件：详情 404、搜索排除、by-code 带 deleted 标志
        mockMvc.perform(get("/api/items/{id}", id).session(boss))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/items/search").param("kw", code).session(boss))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/items/by-code/{code}", code).session(boss))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.deleted").value(true));

        // 回收站列表（A-only 端点本用例先验 boss 侧内容）
        mockMvc.perform(get("/api/items/recycle-bin").session(boss))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].itemCode").value(code))
                .andExpect(jsonPath("$.data.rows[0].reason").value("テスト整理"));

        restore(boss, id, "res-1");
        row = jdbcTemplate.queryForMap("SELECT deleted, stock_status, warehouse FROM item WHERE id = ?", id);
        assertThat(((Number) row.get("deleted")).intValue()).isEqualTo(0);
        assertThat(((Number) row.get("stock_status")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("warehouse")).intValue()).isEqualTo(1);
        ledger = ledgerOf("res-1");
        assertThat(num(ledger, "txn_type")).isEqualTo(14);
        assertThat(ledger.get("wh_from")).isNull();
        assertThat(num(ledger, "wh_to")).isEqualTo(1);
        assertThat(num(ledger, "qty_change")).isEqualTo(1);
        assertThat(consistencyService.check().ok()).as("恢复后对账不变量仍成立").isTrue();
        mockMvc.perform(get("/api/items/{id}", id).session(boss))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'RECYCLE_DELETE'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'RECYCLE_RESTORE'", Integer.class)).isEqualTo(1);
    }

    @Test
    void recycleDeleteRestore_transitItem_zeroQtyLedger() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "c-1", htVenueId, 1000, 1, "");

        recycleDelete(boss, id, "del-1", null);
        Map<String, Object> ledger = ledgerOf("del-1");
        assertThat(num(ledger, "txn_type")).isEqualTo(13);
        assertThat(ledger.get("wh_from")).isNull();
        assertThat(num(ledger, "qty_change")).isEqualTo(0);
        assertThat(num(ledger, "stock_from")).isEqualTo(0);
        assertThat(consistencyService.check().ok()).isTrue();

        restore(boss, id, "res-1");
        ledger = ledgerOf("res-1");
        assertThat(num(ledger, "txn_type")).isEqualTo(14);
        assertThat(ledger.get("wh_to")).isNull();
        assertThat(num(ledger, "qty_change")).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_status FROM item WHERE id = ?", Integer.class, id)).isEqualTo(0);
        assertThat(consistencyService.check().ok()).isTrue();
    }

    /**
     * 记账关键用例（D-064 1）：已作废的在库件软删/恢复必须记 0——
     * VOID 已按 (该仓,−1) 出账且 truth 口径（voided=0）已排除该件，
     * 若 RECYCLE_DELETE 再记 −1 即双重扣减，Σledger≠COUNT 不变量破。
     */
    @Test
    void recycleDeleteRestore_voidedItem_keepsVoidedFlagAndZeroLedger() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "c-1", htVenueId, 1000, 1, "");
        arrive(boss, id, "arr-1", 1);
        voidItem(boss, id, "v-1", "間違い");
        assertThat(consistencyService.check().ok()).isTrue();

        recycleDelete(boss, id, "del-1", "重複登録の整理");
        Map<String, Object> ledger = ledgerOf("del-1");
        assertThat(num(ledger, "txn_type")).isEqualTo(13);
        assertThat(ledger.get("wh_from")).isNull();
        assertThat(num(ledger, "qty_change")).isEqualTo(0);
        assertThat(consistencyService.check().ok()).as("作废件软删不得双重扣减").isTrue();

        restore(boss, id, "res-1");
        // 恢复后仍作废（voided/deleted 两治理轴正交）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT voided FROM item WHERE id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted FROM item WHERE id = ?", Integer.class, id)).isEqualTo(0);
        ledger = ledgerOf("res-1");
        assertThat(num(ledger, "qty_change")).isEqualTo(0);
        assertThat(consistencyService.check().ok()).isTrue();
    }

    @Test
    void recycleReplay_sameKeyReadsBack_crossTypeKeyRejected() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "c-1", htVenueId, 1000, 1, "");
        arrive(boss, id, "arr-1", 1);

        // 同键重放：读回原结果，流水仅一行
        recycleDelete(boss, id, "del-1", "テスト");
        recycleDelete(boss, id, "del-1", "テスト");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE client_req_id = 'del-1'", Integer.class)).isEqualTo(1);
        restore(boss, id, "res-1");
        restore(boss, id, "res-1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE client_req_id = 'res-1'", Integer.class)).isEqualTo(1);

        // 跨操作类型键占用（D-045 C 全局键分类）：res-1 已被恢复动作消费
        mockMvc.perform(delete("/api/items/{id}", id).session(boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"res-1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recycleDoubleDelete409014_restoreLive409015_unknownItem404() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "c-1", htVenueId, 1000, 1, "");
        arrive(boss, id, "arr-1", 1);
        recycleDelete(boss, id, "del-1", null);

        // 新键重复软删 → 409014
        mockMvc.perform(delete("/api/items/{id}", id).session(boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"del-2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409014));

        // 恢复非软删件 → 409015
        long live = createItem(boss, "c-2", htVenueId, 1000, 1, "");
        mockMvc.perform(post("/api/items/{id}/restore", live).session(boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"res-2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409015));

        mockMvc.perform(delete("/api/items/999999").session(boss)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"del-3\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void recycleBinAndActions_adminOnly() throws Exception {
        MockHttpSession boss = loginAs("boss");
        MockHttpSession eichi = loginAs("eichi");
        long dead = createItem(boss, "c-1", htVenueId, 1000, 1, "");
        long live = createItem(boss, "c-2", htVenueId, 1000, 1, "");
        recycleDelete(boss, dead, "del-1", "整理");

        // 编辑者：列表/软删/恢复全部 403
        mockMvc.perform(get("/api/items/recycle-bin").session(eichi))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/items/{id}", live).session(eichi)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"del-9\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/items/{id}/restore", dead).session(eichi)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"res-9\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/items/{id}", dead).session(eichi)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"del-8\"}"))
                .andExpect(status().isForbidden());

        // 管理员：列表仅含软删件，分页信封完整
        mockMvc.perform(get("/api/items/recycle-bin").param("page", "1").param("size", "20").session(boss))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.rows[0].itemCode").value("HTK9-A1X"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted FROM item WHERE id = ?", Integer.class, live)).isEqualTo(0);
    }
}
