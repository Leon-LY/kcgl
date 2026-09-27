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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 作废与重录集成测试（M2-6，docs/01 7.1 作废重录全路径）：
 * void 端点（幂等 clientReqId/冻结校验/VOID 流水在库 −1 其余 0）
 * + 重录（reEntryOf：服务端继承不可见字段/图片行复制/void_re_entry 反链/remark 互写）。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class VoidAndReEntryIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String PASSWORD = "Void-1234-x";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;

    long venueId;

    @BeforeEach
    void resetFixtures() {
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM stock_ledger");
        jdbcTemplate.update("DELETE FROM item_image");
        jdbcTemplate.update("DELETE FROM item");
        jdbcTemplate.update("DELETE FROM seq_item_code");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN ('boss','eichi','miru')");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('boss', ?, '管理者', 1, 1, 0), ('eichi', ?, '編集者', 2, 1, 0), ('miru', ?, '閲覧者', 3, 1, 0)
                """, ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD));
        jdbcTemplate.update("DELETE FROM auction_venue");
        jdbcTemplate.update("DELETE FROM price_band");
        jdbcTemplate.update("DELETE FROM year_code");
        jdbcTemplate.update("INSERT INTO year_code(`year`, code) VALUES (2016,'A'),(2026,'K'),(2027,'L')");
        jdbcTemplate.update("INSERT INTO price_band(code, lower_bound, upper_bound, enabled) VALUES ('X', 0, 3000, 1)");
        jdbcTemplate.update("INSERT INTO auction_venue(code, name, enabled) VALUES ('HT', '飛騨古民具市', 1)");
        venueId = jdbcTemplate.queryForObject("SELECT id FROM auction_venue WHERE code = 'HT'", Long.class);
    }

    private MockHttpSession loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .param("username", username).param("password", PASSWORD))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("登录应成功: %s",
                result.getResponse().getContentAsString()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession();
    }

    private long createItem(MockHttpSession session, String clientReqId, String extra) throws Exception {
        String body = """
                {"clientReqId":"%s","venueId":%d,"buyDate":"%s","purchasePrice":1000,"warehouse":1,
                 "photoDate":"2026-09-10","fee":300,"shippingFee":200,"tax":10,
                 "shelfNo":"S-3","groupNo":"G7","remark":"骨董品の壺","itemName":"伊万里染付壺"%s}
                """.formatted(clientReqId, venueId, LocalDate.now().toString(), extra)
                .replace("\n", "");
        String json = mockMvc.perform(post("/api/items").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(json, "$.data.id")).longValue();
    }

    private void voidItem(MockHttpSession session, long itemId, String clientReqId, String reason)
            throws Exception {
        mockMvc.perform(post("/api/items/{id}/void", itemId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"%s\",\"reason\":\"%s\"}"
                                .formatted(clientReqId, reason)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.voided").value(true))
                .andExpect(jsonPath("$.data.voidReason").value(reason));
    }

    // ------------------------------------------------------------------ void 端点

    @Test
    void voidItem_inTransitByEditor_frozenVoidLedgerQtyZero() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long itemId = createItem(editor, "void-1", "");

        voidItem(editor, itemId, "v-req-1", "価格入力ミス");

        var ledger = jdbcTemplate.queryForMap(
                "SELECT stock_from, stock_to, wh_from, wh_to, qty_change, reason, operator_name"
                        + " FROM stock_ledger WHERE item_id=? AND txn_type=8", itemId);
        assertThat(ledger).containsEntry("stock_from", 0);   // 在途
        assertThat(ledger).containsEntry("qty_change", 0);   // 不占仓账
        assertThat(ledger.get("wh_from")).isNull();
        assertThat(ledger).containsEntry("reason", "価格入力ミス");
        assertThat(ledger).containsEntry("operator_name", "編集者");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action='ITEM_VOID' AND entity_id=?",
                Long.class, itemId)).isEqualTo(1L);
    }

    @Test
    void voidItem_inStock_voidLedgerWhMinusOne() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long itemId = createItem(editor, "void-2", "");
        jdbcTemplate.update("UPDATE item SET stock_status=1, warehouse=2 WHERE id=?", itemId);

        voidItem(editor, itemId, "v-req-2", "重复录入");

        var ledger = jdbcTemplate.queryForMap(
                "SELECT wh_from, qty_change FROM stock_ledger WHERE item_id=? AND txn_type=8", itemId);
        assertThat(ledger).containsEntry("wh_from", 2);      // 对账不变量：该仓 −1 入账
        assertThat(ledger).containsEntry("qty_change", -1);
        // 冻结：库存态保持原值（1=在库），不发生迁移
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_status FROM item WHERE id=?", Integer.class, itemId)).isEqualTo(1);
    }

    @Test
    void voidItem_alreadyVoided_409006() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long itemId = createItem(editor, "void-3", "");
        voidItem(editor, itemId, "v-req-3a", "価格入力ミス");

        mockMvc.perform(post("/api/items/{id}/void", itemId).session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"v-req-3b\",\"reason\":\"再操作\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409006));
    }

    @Test
    void voidItem_idempotentReplay_originalResult_singleLedgerRow() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long itemId = createItem(editor, "void-4", "");

        mockMvc.perform(post("/api/items/{id}/void", itemId).session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"same-req\",\"reason\":\"価格入力ミス\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.voided").value(true));
        mockMvc.perform(post("/api/items/{id}/void", itemId).session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"same-req\",\"reason\":\"価格入力ミス\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.voided").value(true));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE item_id=? AND txn_type=8",
                Long.class, itemId)).isEqualTo(1L);
    }

    @Test
    void voidItem_emptyReason_400() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long itemId = createItem(editor, "void-5", "");

        mockMvc.perform(post("/api/items/{id}/void", itemId).session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"v-req-5\",\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT voided FROM item WHERE id=?", Integer.class, itemId)).isEqualTo(0);
    }

    @Test
    void voidItem_byViewer_403_missingItem_404() throws Exception {
        MockHttpSession viewer = loginAs("miru");
        MockHttpSession editor = loginAs("eichi");
        long itemId = createItem(editor, "void-6", "");

        mockMvc.perform(post("/api/items/{id}/void", itemId).session(viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"v-req-6\",\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/items/99999/void").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"v-req-6b\",\"reason\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404001));
    }

    // ------------------------------------------------------------------ 重录（reEntryOf）

    @Test
    void reEntry_fullChain_newCodeLinked_fieldsInherited_imagesCopied() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long oldId = createItem(editor, "re-1", "");
        String oldCode = jdbcTemplate.queryForObject(
                "SELECT item_code FROM item WHERE id=?", String.class, oldId);
        // 旧件已传 2 张图（模拟上传完成态；stored_path 任意占位）
        jdbcTemplate.update("""
                INSERT INTO item_image(item_id, client_uuid, stored_path, thumb_path, image_type, sort_order, created_by, created_at)
                VALUES (?,'old-a','2026/09/a.jpg','2026/09/a.jpg',1,0,1,NOW(3)),
                       (?,'old-b','2026/09/b.jpg','2026/09/b.jpg',1,1,1,NOW(3))
                """, oldId, oldId);
        voidItem(editor, oldId, "re-req-1", "価格ミス");

        // 重录：只改单价（会落到同档位），fee/图片等不随请求——服务端继承
        String body = """
                {"clientReqId":"re-2","reEntryOf":%d,"venueId":%d,"buyDate":"%s",
                 "purchasePrice":2000,"warehouse":1,"remark":"金額修正済"}
                """.formatted(oldId, venueId, LocalDate.now().toString()).replace("\n", "");
        String json = mockMvc.perform(post("/api/items").session(editor)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long newId = ((Number) com.jayway.jsonpath.JsonPath.read(json, "$.data.id")).longValue();
        String newCode = com.jayway.jsonpath.JsonPath.read(json, "$.data.itemCode");

        assertThat(newCode).isNotEqualTo(oldCode);
        var newRow = jdbcTemplate.queryForMap(
                "SELECT re_entry_of, photo_date, fee, shipping_fee, tax, shelf_no, group_no,"
                        + " remark, item_name, stock_status FROM item WHERE id=?", newId);
        assertThat(((Number) newRow.get("re_entry_of")).longValue()).isEqualTo(oldId);
        // 请求未带的字段全部继承原件（photoDate/费用/货架/组号/名称——表单外的 ext 字段不可丢）
        assertThat(newRow).containsEntry("photo_date", java.sql.Date.valueOf("2026-09-10"));
        assertThat(newRow).containsEntry("fee", 300L);   // INT UNSIGNED → Long
        assertThat(newRow).containsEntry("shipping_fee", 200L);
        assertThat(newRow).containsEntry("tax", 10L);
        assertThat(newRow).containsEntry("shelf_no", "S-3");
        assertThat(newRow).containsEntry("group_no", "G7");
        assertThat(newRow).containsEntry("item_name", "伊万里染付壺");
        assertThat(newRow.get("remark").toString()).contains("金額修正済").contains(oldCode);
        assertThat(newRow).containsEntry("stock_status", 0);   // 新件重新从在途开始

        // 旧件反链 + remark 互写
        var oldRow = jdbcTemplate.queryForMap(
                "SELECT void_re_entry, remark FROM item WHERE id=?", oldId);
        assertThat(((Number) oldRow.get("void_re_entry")).longValue()).isEqualTo(newId);
        assertThat(oldRow.get("remark").toString()).contains("骨董品の壺").contains(newCode);

        // 图片行复制：新件 2 行、路径与顺序保留、client_uuid 换新（幂等键不复用）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM item_image WHERE item_id=?", Long.class, newId)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM item_image WHERE item_id=? AND stored_path IN ('2026/09/a.jpg','2026/09/b.jpg')",
                Long.class, newId)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM item_image WHERE client_uuid IN ('old-a','old-b')",
                Long.class)).isEqualTo(2L);   // 原行保留在旧件名下（历史可追溯）

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action='ITEM_RE_ENTRY' AND entity_id=?",
                Long.class, newId)).isEqualTo(1L);
    }

    @Test
    void reEntry_notVoidedSource_409007_missingSource_404() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long itemId = createItem(editor, "re-3", "");
        String base = """
                {"clientReqId":"%s","reEntryOf":%d,"venueId":%d,"buyDate":"%s",
                 "purchasePrice":1000,"warehouse":1}
                """.formatted("re-x", itemId, venueId, LocalDate.now().toString()).replace("\n", "");

        mockMvc.perform(post("/api/items").session(editor)
                        .contentType(MediaType.APPLICATION_JSON).content(base))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409007));

        String missing = base.replaceFirst("\"reEntryOf\":\\d+", "\"reEntryOf\":99999");
        mockMvc.perform(post("/api/items").session(editor)
                        .contentType(MediaType.APPLICATION_JSON).content(missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404001));
    }

    @Test
    void itemDetail_voidedItemVisible_softDeleted404() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long itemId = createItem(editor, "re-4", "");
        voidItem(editor, itemId, "re-req-4", "誤入力");

        mockMvc.perform(get("/api/items/{id}", itemId).session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.voided").value(true))
                .andExpect(jsonPath("$.data.voidReason").value("誤入力"))
                .andExpect(jsonPath("$.data.reEntryOf").value(org.hamcrest.Matchers.nullValue()));

        jdbcTemplate.update("UPDATE item SET deleted=1, deleted_at=NOW(3) WHERE id=?", itemId);
        mockMvc.perform(get("/api/items/{id}", itemId).session(editor))
                .andExpect(status().isNotFound());
    }
}
