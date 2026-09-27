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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 录入端点集成测试（M2-3）：POST /api/items 角色矩阵/参数校验/前置校验错误码透传/
 * clientReqId 幂等重放/生成列回填；GET /api/item-codes/preview 预览语义（≠保留）。
 * M2-7：GET /api/items 打印列表——JST 日界区间/会场筛选/作废软删排除/分页/首图缩略图/三角色可查。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class ItemControllerIntegrationTest {

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

    private String body(String clientReqId, String buyDate, String price, String warehouse) {
        return body(clientReqId, buyDate, price, warehouse, venueId);
    }

    private String body(String clientReqId, String buyDate, String price, String warehouse, long vid) {
        return """
                {"clientReqId":"%s","venueId":%d,"buyDate":"%s","purchasePrice":%s,"warehouse":%s,
                 "fee":300,"shippingFee":200,"remark":"連続録入口ニア"}
                """.formatted(clientReqId == null ? "" : clientReqId, vid, buyDate, price, warehouse)
                .replace("\n", "");
    }

    /** 录一件（在途）并返回 id。 */
    private long createItem(MockHttpSession session, String clientReqId) throws Exception {
        String json = mockMvc.perform(post("/api/items").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(clientReqId, "2026-09-15", "1000", "1")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void editor录一件成功_返回最终管理号与生成列() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        String json = mockMvc.perform(post("/api/items").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("api-1", "2026-09-15", "1000", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.itemCode").value("HTK9-A1X"))
                .andExpect(jsonPath("$.data.priceBandCode").value("X"))
                .andExpect(jsonPath("$.data.stockStatus").value(0))
                .andExpect(jsonPath("$.data.voided").value(false))
                // 生成列回填：1000+300+200，profit 未售为 null
                .andExpect(jsonPath("$.data.totalCost").value(1500))
                .andExpect(jsonPath("$.data.profit").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
        // 台账与审计同事务落库
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 1 AND item_id = ?", Long.class, id))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'ITEM_CREATE' AND entity_id = ?",
                Long.class, id)).isEqualTo(1);
    }

    @Test
    void viewer录件_403() throws Exception {
        MockHttpSession viewer = loginAs("miru");
        mockMvc.perform(post("/api/items").session(viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("api-2", "2026-09-15", "1000", "1")))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM item", Long.class)).isZero();
    }

    @Test
    void 参数校验失败_400() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        mockMvc.perform(post("/api/items").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("api-3", "2026-09-15", "0", "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        mockMvc.perform(post("/api/items").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("api-4", "2026-09-15", "1000", "3")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM item", Long.class)).isZero();
    }

    @Test
    void 落札日未来_400() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        // +2 日：对 JST 与本机时区均严格为未来，规避 00-01 时区窗口 flaky
        String future = LocalDate.now().plusDays(2).toString();
        mockMvc.perform(post("/api/items").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("api-5", future, "1000", "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
    }

    @Test
    void 会场不存在_404003() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        mockMvc.perform(post("/api/items").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientReqId":"api-6","venueId":999999,"buyDate":"2026-09-15",
                                 "purchasePrice":1000,"warehouse":1}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404003));
    }

    @Test
    void 年代号缺失_404004() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        mockMvc.perform(post("/api/items").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("api-7", "2015-07-01", "1000", "1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404004));
    }

    @Test
    void 档位不匹配_404002() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        mockMvc.perform(post("/api/items").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("api-8", "2026-09-15", "5000", "1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404002));
    }

    @Test
    void clientReqId重放_同件同号零新行() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        String first = mockMvc.perform(post("/api/items").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("replay-api", "2026-09-15", "1000", "1")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/items").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("replay-api", "2026-09-15", "1000", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(extract(first, "id")).isEqualTo(extract(second, "id"));
        assertThat(extract(first, "itemCode")).isEqualTo(extract(second, "itemCode"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM item", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 1", Long.class)).isEqualTo(1);
    }

    @Test
    void 预览_计数器现值加一_保存后推进() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        mockMvc.perform(get("/api/item-codes/preview").session(editor)
                        .param("venueId", String.valueOf(venueId))
                        .param("buyDate", "2026-09-15")
                        .param("price", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("HTK9-A1X"))
                .andExpect(jsonPath("$.data.seqPrefix").value("A"))
                .andExpect(jsonPath("$.data.seqNo").value(1));
        mockMvc.perform(post("/api/items").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("pv-1", "2026-09-15", "1000", "1")))
                .andExpect(status().isOk());
        // 保存推进后预览跟上（预览≠保留的动态面）
        mockMvc.perform(get("/api/item-codes/preview").session(editor)
                        .param("venueId", String.valueOf(venueId))
                        .param("buyDate", "2026-09-15")
                        .param("price", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("HTK9-A2X"));
        // 月不补零
        mockMvc.perform(get("/api/item-codes/preview").session(editor)
                        .param("venueId", String.valueOf(venueId))
                        .param("buyDate", "2026-10-02")
                        .param("price", "2999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("HTK10-A1X"));
    }

    @Test
    void 预览_viewer无权_403() throws Exception {
        MockHttpSession viewer = loginAs("miru");
        mockMvc.perform(get("/api/item-codes/preview").session(viewer)
                        .param("venueId", String.valueOf(venueId))
                        .param("buyDate", "2026-09-15")
                        .param("price", "1000"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 预览_年代号缺失_404004() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        mockMvc.perform(get("/api/item-codes/preview").session(editor)
                        .param("venueId", String.valueOf(venueId))
                        .param("buyDate", "2015-07-01")
                        .param("price", "1000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404004));
    }

    // ------------------------------------------------------------- M2-7 打印列表

    /** 今日 JST 前后各让一天，规避测试执行跨 JST 午夜的窗口（created_at=应用时钟当下）。 */
    private String todayParam(long offsetDays) {
        return LocalDate.now(ZoneId.of("Asia/Tokyo")).plusDays(offsetDays).toString();
    }

    @Test
    void 列表_创建日区间JST日界_左闭右开按日过滤() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long idEdgeA = createItem(editor, "list-a");
        long idEdgeB = createItem(editor, "list-b");
        long idLater = createItem(editor, "list-c");
        // 钉死三件 created_at：20 日最后一毫秒 / 21 日第一毫秒 / 22 日中午
        jdbcTemplate.update("UPDATE item SET created_at = '2026-09-20 23:59:59.500' WHERE id = ?", idEdgeA);
        jdbcTemplate.update("UPDATE item SET created_at = '2026-09-21 00:00:00.000' WHERE id = ?", idEdgeB);
        jdbcTemplate.update("UPDATE item SET created_at = '2026-09-22 12:00:00.000' WHERE id = ?", idLater);
        // [20 00:00, 21 00:00)：仅 20 日件
        mockMvc.perform(get("/api/items").session(editor)
                        .param("createdFrom", "2026-09-20").param("createdTo", "2026-09-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].id").value(idEdgeA));
        // to=21 含 21 日全天（to+1 00:00 开区间）
        mockMvc.perform(get("/api/items").session(editor)
                        .param("createdFrom", "2026-09-20").param("createdTo", "2026-09-21"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.rows[0].id").value(idEdgeA))
                .andExpect(jsonPath("$.data.rows[1].id").value(idEdgeB));
        // 区间外零行
        mockMvc.perform(get("/api/items").session(editor)
                        .param("createdFrom", "2026-09-23").param("createdTo", "2026-09-25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.rows").isEmpty());
    }

    @Test
    void 列表_录入顺序id升序_分页与size上限钳制() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        createItem(editor, "pg-a");
        createItem(editor, "pg-b");
        createItem(editor, "pg-c");
        // 录入顺序（id 升序）= 标签贴件顺序
        mockMvc.perform(get("/api/items").session(editor)
                        .param("createdFrom", todayParam(-1)).param("createdTo", todayParam(1))
                        .param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.rows", hasSize(2)))
                .andExpect(jsonPath("$.data.rows[0].itemCode").value("HTK9-A1X"))
                .andExpect(jsonPath("$.data.rows[1].itemCode").value("HTK9-A2X"));
        mockMvc.perform(get("/api/items").session(editor)
                        .param("createdFrom", todayParam(-1)).param("createdTo", todayParam(1))
                        .param("size", "2").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows", hasSize(1)))
                .andExpect(jsonPath("$.data.rows[0].itemCode").value("HTK9-A3X"));
        // size 钳制到 100（响应回显钳后值）
        mockMvc.perform(get("/api/items").session(editor)
                        .param("createdFrom", todayParam(-1)).param("createdTo", todayParam(1))
                        .param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    @Test
    void 列表_会场筛选() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        jdbcTemplate.update("INSERT INTO auction_venue(code, name, enabled) VALUES ('NG', '名古屋骨董市', 1)");
        Long ngId = jdbcTemplate.queryForObject("SELECT id FROM auction_venue WHERE code = 'NG'", Long.class);
        createItem(editor, "venue-a");
        createItem(editor, "venue-b");
        mockMvc.perform(post("/api/items").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("venue-ng", "2026-09-15", "1000", "1", ngId)))
                .andExpect(status().isOk());
        // 全会场=3，NG=1（且 venueCode 为 NG 快照）
        mockMvc.perform(get("/api/items").session(editor)
                        .param("createdFrom", todayParam(-1)).param("createdTo", todayParam(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3));
        mockMvc.perform(get("/api/items").session(editor)
                        .param("createdFrom", todayParam(-1)).param("createdTo", todayParam(1))
                        .param("venueId", String.valueOf(ngId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].venueCode").value("NG"));
    }

    @Test
    void 列表_作废件与软删件不出标签() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long liveId = createItem(editor, "live-a");
        long voidedId = createItem(editor, "void-b");
        long deletedId = createItem(editor, "del-c");
        // 真实作废链路（作废走端点；软删无端点 M5 前直改标志位）
        mockMvc.perform(post("/api/items/" + voidedId + "/void").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"void-list\",\"reason\":\"誤入力\"}"))
                .andExpect(status().isOk());
        jdbcTemplate.update("UPDATE item SET deleted = 1 WHERE id = ?", deletedId);
        mockMvc.perform(get("/api/items").session(editor)
                        .param("createdFrom", todayParam(-1)).param("createdTo", todayParam(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].id").value(liveId));
    }

    @Test
    void 列表_缩略图取sort_order最小首图() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long id = createItem(editor, "thumb-a");
        long editorId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'eichi'", Long.class);
        // 乱序插入（sort 2 先、sort 1 后），首图应为 sort=1
        jdbcTemplate.update("""
                INSERT INTO item_image(item_id, client_uuid, stored_path, thumb_path, sort_order, created_by)
                VALUES (?, '11111111-1111-1111-1111-111111111112', '2026/09/b.jpg', '2026/09/b_t.jpg', 2, ?),
                       (?, '11111111-1111-1111-1111-111111111111', '2026/09/a.jpg', '2026/09/a_t.jpg', 1, ?)
                """, id, editorId, id, editorId);
        mockMvc.perform(get("/api/items").session(editor)
                        .param("createdFrom", todayParam(-1)).param("createdTo", todayParam(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].thumbUrl").value("/img/thumb/2026/09/a_t.jpg"));
        // 无图件 thumbUrl 为 null（38×21 排版无图位）
        createItem(editor, "thumb-b");
        mockMvc.perform(get("/api/items").session(editor)
                        .param("createdFrom", todayParam(-1)).param("createdTo", todayParam(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[1].thumbUrl").isEmpty());
    }

    @Test
    void 列表_viewer可查_打印是全员能力() throws Exception {
        MockHttpSession viewer = loginAs("miru");
        mockMvc.perform(get("/api/items").session(viewer)
                        .param("createdFrom", todayParam(-1)).param("createdTo", todayParam(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void 列表_起止颠倒与缺参_400() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        mockMvc.perform(get("/api/items").session(editor)
                        .param("createdFrom", "2026-09-21").param("createdTo", "2026-09-20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        // 回归：缺参曾被 GlobalExceptionHandler 兜底吞成 500（应 400，且不得进 ERROR 日志）
        mockMvc.perform(get("/api/items").session(editor)
                        .param("createdFrom", "2026-09-20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
    }

    private static String extract(String json, String field) {
        return json.replaceAll(".*\"" + field + "\":\"?([^,\"}]*)\"?.*", "$1");
    }
}
