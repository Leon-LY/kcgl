package com.kcgl;

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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 到货核对端点集成测试（M2-8a）：GET /api/inventory/arrivals/pending 在途清单
 * （仓库筛选/倒序/排除已入库·作废·软删/缩略图/分页/三角色）；
 * POST /api/inventory/arrivals 批量确认入库（全成全败原子性/ARRIVAL 流水五元组/
 * 幂等重放 200 出清/409008 整批回滚/到仓改仓/入库日三分支/权限矩阵/请求形校验）。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class InventoryControllerIntegrationTest {

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

    private String createBody(String clientReqId, String warehouse, String warehouseInDate) {
        return ("{\"clientReqId\":\"" + clientReqId + "\",\"venueId\":" + venueId
                + ",\"buyDate\":\"2026-09-15\",\"purchasePrice\":1000,\"warehouse\":" + warehouse
                + (warehouseInDate == null ? "" : ",\"warehouseInDate\":\"" + warehouseInDate + "\"")
                + ",\"remark\":\"到貨確認テスト\"}");
    }

    /** 录一件（在途）并返回 id。 */
    private long createItem(MockHttpSession session, String clientReqId, String warehouse) throws Exception {
        String json = mockMvc.perform(post("/api/items").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(clientReqId, warehouse, null)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private String line(long itemId, String clientReqId) {
        return line(itemId, clientReqId, null, null);
    }

    private String line(long itemId, String clientReqId, Integer warehouse, String shelfNo) {
        StringBuilder sb = new StringBuilder("{\"itemId\":").append(itemId)
                .append(",\"clientReqId\":\"").append(clientReqId).append("\"");
        if (warehouse != null) {
            sb.append(",\"warehouse\":").append(warehouse);
        }
        if (shelfNo != null) {
            sb.append(",\"shelfNo\":\"").append(shelfNo).append("\"");
        }
        return sb.append("}").toString();
    }

    private String arriveBody(String[] lines, String warehouseInDate) {
        return "{\"items\":[" + String.join(",", lines) + "]"
                + (warehouseInDate == null ? "" : ",\"warehouseInDate\":\"" + warehouseInDate + "\"")
                + "}";
    }

    // ------------------------------------------------------------- 在途清单

    @Test
    void pending_warehouseFilterNewestFirst_pagination() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long first = createItem(editor, "pnd-a", "1");
        long wh2 = createItem(editor, "pnd-b", "2");
        long newest = createItem(editor, "pnd-c", "1");
        // 全量：id 倒序（新录入在前，到货核对先看最新批次）
        mockMvc.perform(get("/api/inventory/arrivals/pending").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.rows", hasSize(3)))
                .andExpect(jsonPath("$.data.rows[0].id").value(newest))
                .andExpect(jsonPath("$.data.rows[1].id").value(wh2))
                .andExpect(jsonPath("$.data.rows[2].id").value(first))
                .andExpect(jsonPath("$.data.rows[0].itemCode").exists())
                .andExpect(jsonPath("$.data.rows[0].buyDate").value("2026-09-15"))
                .andExpect(jsonPath("$.data.rows[0].thumbUrl").isEmpty());
        // 预计仓库筛选（福岡）
        mockMvc.perform(get("/api/inventory/arrivals/pending").session(editor)
                        .param("warehouse", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].id").value(wh2))
                .andExpect(jsonPath("$.data.rows[0].warehouse").value(2));
        // 分页：size=1 第 2 页=中间件
        mockMvc.perform(get("/api/inventory/arrivals/pending").session(editor)
                        .param("size", "1").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.rows", hasSize(1)))
                .andExpect(jsonPath("$.data.rows[0].id").value(wh2));
        // 仓库参数非法
        mockMvc.perform(get("/api/inventory/arrivals/pending").session(editor)
                        .param("warehouse", "3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
    }

    @Test
    void pending_thumbnail_usesFirstSortedImage() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long id = createItem(editor, "pnd-thumb", "1");
        long editorId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'eichi'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO item_image(item_id, client_uuid, stored_path, thumb_path, sort_order, created_by)
                VALUES (?, '22222222-2222-2222-2222-222222222221', '2026/09/b.jpg', '2026/09/b_t.jpg', 2, ?),
                       (?, '22222222-2222-2222-2222-222222222222', '2026/09/a.jpg', '2026/09/a_t.jpg', 1, ?)
                """, id, editorId, id, editorId);
        mockMvc.perform(get("/api/inventory/arrivals/pending").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].thumbUrl").value("/img/thumb/2026/09/a_t.jpg"));
    }

    @Test
    void pending_excludesArrivedVoidedSoftDeleted() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long arrivedId = createItem(editor, "exc-a", "1");
        long voidedId = createItem(editor, "exc-b", "1");
        long deletedId = createItem(editor, "exc-c", "1");
        long pendingId = createItem(editor, "exc-d", "1");
        mockMvc.perform(post("/api/inventory/arrivals").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arriveBody(new String[]{line(arrivedId, "arr-exc-a")}, null)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/items/" + voidedId + "/void").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"void-exc\",\"reason\":\"誤入力\"}"))
                .andExpect(status().isOk());
        jdbcTemplate.update("UPDATE item SET deleted = 1 WHERE id = ?", deletedId);
        mockMvc.perform(get("/api/inventory/arrivals/pending").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].id").value(pendingId));
    }

    @Test
    void pending_viewerAllowed_unauthenticated401() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        createItem(editor, "pnd-v", "1");
        MockHttpSession viewer = loginAs("miru");
        mockMvc.perform(get("/api/inventory/arrivals/pending").session(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/inventory/arrivals/pending"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------- 批量确认入库

    @Test
    void arriveBatch_twoItems_ledgerFiveTupleAndAuditWritten() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long id1 = createItem(editor, "arr-1", "1");
        long id2 = createItem(editor, "arr-2", "1");
        LocalDate todayJst = LocalDate.now(ZoneId.of("Asia/Tokyo"));
        mockMvc.perform(post("/api/inventory/arrivals").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arriveBody(new String[]{line(id1, "arr-key-1"), line(id2, "arr-key-2")}, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.arrivedCount").value(2))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].itemId").value(id1))
                .andExpect(jsonPath("$.data.items[0].stockStatus").value(1))
                .andExpect(jsonPath("$.data.items[1].stockStatus").value(1));
        // item：在库 + 入库日缺省今天 JST + version 推进
        for (long id : new long[]{id1, id2}) {
            Map<String, Object> item = jdbcTemplate.queryForMap(
                    "SELECT stock_status, warehouse, warehouse_in_date, version FROM item WHERE id = ?", id);
            assertThat(((Number) item.get("stock_status")).intValue()).isEqualTo(1);
            assertThat(((Number) item.get("warehouse")).intValue()).isEqualTo(1);
            assertThat(((java.sql.Date) item.get("warehouse_in_date")).toLocalDate()).isEqualTo(todayJst);
            assertThat(((Number) item.get("version")).intValue()).isEqualTo(1);
        }
        // ARRIVAL 流水：在途不占仓账（wh_from=NULL）→目标仓 +1
        Map<String, Object> ledger = jdbcTemplate.queryForMap("""
                SELECT stock_from, stock_to, wh_from, wh_to, qty_change
                FROM stock_ledger WHERE txn_type = 2 AND item_id = ?
                """, id2);
        assertThat(((Number) ledger.get("stock_from")).intValue()).isZero();
        assertThat(((Number) ledger.get("stock_to")).intValue()).isEqualTo(1);
        assertThat(ledger.get("wh_from")).isNull();
        assertThat(((Number) ledger.get("wh_to")).intValue()).isEqualTo(1);
        assertThat(((Number) ledger.get("qty_change")).intValue()).isEqualTo(1);
        // 审计逐件
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'ITEM_ARRIVAL' AND entity_id IN (?, ?)",
                Long.class, id1, id2)).isEqualTo(2);
        // 入库后清单清空
        mockMvc.perform(get("/api/inventory/arrivals/pending").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void arriveBatch_idempotentReplay_originalResult200_ledgerNotDuplicated() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long id = createItem(editor, "arr-rp", "1");
        String first = mockMvc.perform(post("/api/inventory/arrivals").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arriveBody(new String[]{line(id, "arr-replay")}, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.arrivedCount").value(1))
                .andReturn().getResponse().getContentAsString();
        // 网络超时重放：同 clientReqId 整批重发 → 200 原结果，不重复入账
        String second = mockMvc.perform(post("/api/inventory/arrivals").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arriveBody(new String[]{line(id, "arr-replay")}, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.arrivedCount").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(first).isEqualTo(second);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 2", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'ITEM_ARRIVAL'", Long.class)).isEqualTo(1);
    }

    @Test
    void arriveBatch_inStockItemMixedIn_409008WholeBatchRolledBack() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long arrivedId = createItem(editor, "arr-mix-a", "1");
        long freshId = createItem(editor, "arr-mix-b", "1");
        mockMvc.perform(post("/api/inventory/arrivals").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arriveBody(new String[]{line(arrivedId, "arr-mix-1")}, null)))
                .andExpect(status().isOk());
        // 新批：第 1 行合法新件，第 2 行已入库件 → 整批回滚（原子性）
        mockMvc.perform(post("/api/inventory/arrivals").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arriveBody(
                                new String[]{line(freshId, "arr-mix-2"), line(arrivedId, "arr-mix-3")},
                                null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409008));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_status FROM item WHERE id = ?", Integer.class, freshId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 2", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'ITEM_ARRIVAL'", Long.class)).isEqualTo(1);
    }

    @Test
    void arriveBatch_warehouseOverrideAndShelfNo_ledgerWhToFollows() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long id = createItem(editor, "arr-wh", "1");
        mockMvc.perform(post("/api/inventory/arrivals").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arriveBody(
                                new String[]{line(id, "arr-wh-key", 2, "F-12")}, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].warehouse").value(2));
        Map<String, Object> item = jdbcTemplate.queryForMap(
                "SELECT warehouse, shelf_no FROM item WHERE id = ?", id);
        assertThat(((Number) item.get("warehouse")).intValue()).isEqualTo(2);
        assertThat(item.get("shelf_no")).isEqualTo("F-12");
        Map<String, Object> ledger = jdbcTemplate.queryForMap(
                "SELECT wh_from, wh_to FROM stock_ledger WHERE txn_type = 2 AND item_id = ?", id);
        assertThat(ledger.get("wh_from")).isNull();
        assertThat(((Number) ledger.get("wh_to")).intValue()).isEqualTo(2);
    }

    @Test
    void arrive_warehouseInDate_explicitPastKept_prefilledNotOverwritten_future400() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        // 显式指定过去日
        long id1 = createItem(editor, "arr-d1", "1");
        mockMvc.perform(post("/api/inventory/arrivals").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arriveBody(new String[]{line(id1, "arr-d1-key")}, "2026-09-01")))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT warehouse_in_date FROM item WHERE id = ?", LocalDate.class, id1))
                .isEqualTo(LocalDate.of(2026, 9, 1));
        // 录入预填入库日：到仓不覆盖（docs/01 4.3）
        long id2 = Long.parseLong(mockMvc.perform(post("/api/items").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("arr-d2", "1", "2026-09-05")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"id\":(\\d+).*", "$1"));
        mockMvc.perform(post("/api/inventory/arrivals").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arriveBody(new String[]{line(id2, "arr-d2-key")}, null)))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT warehouse_in_date FROM item WHERE id = ?", LocalDate.class, id2))
                .isEqualTo(LocalDate.of(2026, 9, 5));
        // 未来日拒绝（+2 日规避时区窗口）
        long id3 = createItem(editor, "arr-d3", "1");
        mockMvc.perform(post("/api/inventory/arrivals").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arriveBody(new String[]{line(id3, "arr-d3-key")},
                                LocalDate.now().plusDays(2).toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_status FROM item WHERE id = ?", Integer.class, id3)).isZero();
    }

    @Test
    void arriveBatch_voidedItem409006_softDeletedItem404() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long voidedId = createItem(editor, "arr-vd", "1");
        long deletedId = createItem(editor, "arr-dl", "1");
        mockMvc.perform(post("/api/items/" + voidedId + "/void").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"void-arr\",\"reason\":\"誤入力\"}"))
                .andExpect(status().isOk());
        jdbcTemplate.update("UPDATE item SET deleted = 1 WHERE id = ?", deletedId);
        mockMvc.perform(post("/api/inventory/arrivals").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arriveBody(new String[]{line(voidedId, "arr-vd-key")}, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409006));
        mockMvc.perform(post("/api/inventory/arrivals").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arriveBody(new String[]{line(deletedId, "arr-dl-key")}, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404001));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 2", Long.class)).isZero();
    }

    @Test
    void arriveBatch_byViewer_403_noSideEffects() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long id = createItem(editor, "arr-perm", "1");
        MockHttpSession viewer = loginAs("miru");
        mockMvc.perform(post("/api/inventory/arrivals").session(viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arriveBody(new String[]{line(id, "arr-perm-key")}, null)))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_status FROM item WHERE id = ?", Integer.class, id)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 2", Long.class)).isZero();
    }

    @Test
    void arriveBatch_requestValidation_emptyBatchMissingKeyDupKeyOverLimitBadWarehouse_400() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long id = createItem(editor, "arr-val", "1");
        // 空批
        mockMvc.perform(post("/api/inventory/arrivals").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arriveBody(new String[]{}, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        // 行缺幂等键
        mockMvc.perform(post("/api/inventory/arrivals").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"itemId\":" + id + "}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        // 批内键重复
        mockMvc.perform(post("/api/inventory/arrivals").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arriveBody(new String[]{line(id, "dup-k"), line(id, "dup-k")}, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        // 仓库非法
        mockMvc.perform(post("/api/inventory/arrivals").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arriveBody(new String[]{line(id, "wh-k", 3, null)}, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        // 超 100 件
        String[] tooMany = new String[101];
        for (int i = 0; i < tooMany.length; i++) {
            tooMany[i] = line(id, "over-k-" + i);
        }
        mockMvc.perform(post("/api/inventory/arrivals").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arriveBody(tooMany, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        // 全部拒绝后零副作用
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 2", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_status FROM item WHERE id = ?", Integer.class, id)).isZero();
    }
}
