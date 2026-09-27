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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 本日录入会话集成测试（M2-8b）：GET /api/items/today-session——
 * created_by=me + 当天 JST 窗口、含作废件的对数口径、他人件排除、隔日件排除、viewer 自身空会话。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class TodaySessionIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String PASSWORD = "Today-1234-x";
    static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;

    long venueId;
    long editorId;
    long adminId;

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
        adminId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = 'boss'", Long.class);
        editorId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = 'eichi'", Long.class);
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

    /** 经 API 录一件（在途）并返回 id。 */
    private long createItem(MockHttpSession session, String clientReqId) throws Exception {
        String body = """
                {"clientReqId":"%s","venueId":%d,"buyDate":"2026-09-01","purchasePrice":1000,"warehouse":1}
                """.formatted(clientReqId, venueId).replace("\n", "");
        MvcResult result = mockMvc.perform(post("/api/items").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return Long.parseLong(mvcJsonField(result.getResponse().getContentAsString(), "id"));
    }

    private void voidItem(MockHttpSession session, long itemId, String reason) throws Exception {
        String body = """
                {"clientReqId":"void-%d","reason":"%s"}
                """.formatted(itemId, reason).replace("\n", "");
        mockMvc.perform(post("/api/items/{id}/void", itemId).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    /** 顶层 data 内字段直取（信封 {code,message,data}）。 */
    private String mvcJsonField(String responseBody, String field) {
        String marker = "\"" + field + "\":";
        int idx = responseBody.indexOf(marker);
        assertThat(idx).as("响应应含字段 %s: %s", field, responseBody).isGreaterThan(-1);
        int start = idx + marker.length();
        int end = start;
        while (end < responseBody.length() && ",}".indexOf(responseBody.charAt(end)) < 0) {
            end++;
        }
        return responseBody.substring(start, end);
    }

    @Test
    void todaySession_returnsMyItemsTodayIncludingVoided_withCounts() throws Exception {
        MockHttpSession session = loginAs("eichi");
        long first = createItem(session, "today-1");
        long second = createItem(session, "today-2");
        createItem(loginAs("boss"), "today-other-user");
        voidItem(session, first, "価格入力ミス");

        mockMvc.perform(get("/api/items/today-session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value(LocalDate.now(JST).toString()))
                .andExpect(jsonPath("$.data.activeCount").value(1))
                .andExpect(jsonPath("$.data.voidedCount").value(1))
                .andExpect(jsonPath("$.data.rows", hasSize(2)))
                // id 升序=录入顺序；作废件带理由
                .andExpect(jsonPath("$.data.rows[0].id").value(first))
                .andExpect(jsonPath("$.data.rows[0].voided").value(true))
                .andExpect(jsonPath("$.data.rows[0].voidReason").value("価格入力ミス"))
                .andExpect(jsonPath("$.data.rows[1].id").value(second))
                .andExpect(jsonPath("$.data.rows[1].voided").value(false))
                .andExpect(jsonPath("$.data.rows[1].thumbUrl").value(nullValue()));
    }

    @Test
    void todaySession_excludesItemsCreatedBeforeToday() throws Exception {
        // 隔日件直插（created_at=昨日 JST），同 created_by 不应计入今天会话
        jdbcTemplate.update("""
                INSERT INTO item(item_code, venue_id, venue_code, `year`, year_code, buy_month,
                    seq_prefix, seq_no, buy_date, purchase_price, price_band_code, warehouse, created_by, created_at)
                VALUES ('HTK9-A9X', ?, 'HT', 2026, 'K', 9, 'A', 9, '2026-09-01', 1000, 'X', 1, ?,
                    DATE_SUB(NOW(3), INTERVAL 1 DAY))
                """, venueId, editorId);

        MockHttpSession session = loginAs("eichi");
        mockMvc.perform(get("/api/items/today-session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeCount").value(0))
                .andExpect(jsonPath("$.data.voidedCount").value(0))
                .andExpect(jsonPath("$.data.rows", hasSize(0)));
    }

    @Test
    void todaySession_viewerSeesOwnEmptySession() throws Exception {
        MockHttpSession session = loginAs("miru");
        mockMvc.perform(get("/api/items/today-session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows", hasSize(0)))
                .andExpect(jsonPath("$.data.activeCount").value(0));
    }
}
