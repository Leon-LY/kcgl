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
 * 录入端点集成测试（M2-3）：POST /api/items 角色矩阵/参数校验/前置校验错误码透传/
 * clientReqId 幂等重放/生成列回填；GET /api/item-codes/preview 预览语义（≠保留）。
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
        return """
                {"clientReqId":"%s","venueId":%d,"buyDate":"%s","purchasePrice":%s,"warehouse":%s,
                 "fee":300,"shippingFee":200,"remark":"連続録入口ニア"}
                """.formatted(clientReqId == null ? "" : clientReqId, venueId, buyDate, price, warehouse)
                .replace("\n", "");
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

    private static String extract(String json, String field) {
        return json.replaceAll(".*\"" + field + "\":\"?([^,\"}]*)\"?.*", "$1");
    }
}
