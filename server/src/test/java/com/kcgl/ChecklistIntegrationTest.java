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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 首启 checklist 集成测试（M2-8b-3）：GET /api/checklist 五步布尔聚合 +
 * POST /api/checklist/print-done 幂等标记 + 仅管理员可达。
 * 基线口径：仅初始管理员一人、空字典、空商品 → 全 false。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class ChecklistIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String PASSWORD = "Check-1234-c";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetToFreshInstall() {
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM stock_ledger");
        jdbcTemplate.update("DELETE FROM item");
        jdbcTemplate.update("DELETE FROM seq_item_code");
        jdbcTemplate.update("DELETE FROM sys_user");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('boss', ?, '管理者', 1, 1, 0), ('eichi', ?, '編集者', 2, 1, 0)
                """, ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD));
        jdbcTemplate.update("DELETE FROM auction_venue");
        jdbcTemplate.update("DELETE FROM price_band");
        jdbcTemplate.update("DELETE FROM year_code");
        jdbcTemplate.update("DELETE FROM sys_setting WHERE `key` = 'checklist.print_done'");
        jdbcTemplate.update("INSERT INTO year_code(`year`, code) VALUES (2026,'K')");
    }

    private MockHttpSession loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .param("username", username).param("password", PASSWORD))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("登录应成功: %s",
                result.getResponse().getContentAsString()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession();
    }

    /** 经 API 录一件（在途）——走真实取号链路，同时置真 hasItem。字典行幂等（用例可能已自建）。 */
    private void createOneItem(MockHttpSession session) throws Exception {
        jdbcTemplate.update("INSERT IGNORE INTO price_band(code, lower_bound, upper_bound, enabled) VALUES ('X', 0, 3000, 1)");
        jdbcTemplate.update("INSERT IGNORE INTO auction_venue(code, name, enabled) VALUES ('HT', '飛騨古民具市', 1)");
        jdbcTemplate.update("UPDATE price_band SET enabled = 1 WHERE code = 'X'");
        jdbcTemplate.update("UPDATE auction_venue SET enabled = 1 WHERE code = 'HT'");
        Long venueId = jdbcTemplate.queryForObject("SELECT id FROM auction_venue WHERE code = 'HT'", Long.class);
        String body = """
                {"clientReqId":"checklist-1","venueId":%d,"buyDate":"2026-09-01","purchasePrice":1000,"warehouse":1}
                """.formatted(venueId).replace("\n", "");
        mockMvc.perform(post("/api/items").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void checklist_allFalseOnFreshInstall_withOnlyBootstrapAdmin() throws Exception {
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = 'eichi'");
        MockHttpSession session = loginAs("boss");

        mockMvc.perform(get("/api/checklist").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasStaffUser").value(false))
                .andExpect(jsonPath("$.data.hasVenue").value(false))
                .andExpect(jsonPath("$.data.hasPriceBand").value(false))
                .andExpect(jsonPath("$.data.hasItem").value(false))
                .andExpect(jsonPath("$.data.printDone").value(false));
    }

    @Test
    void checklist_flagsFlipAsSetupProgresses() throws Exception {
        MockHttpSession session = loginAs("boss");

        // 建账号（第二人）→ hasStaffUser 置真（其余仍假）
        mockMvc.perform(get("/api/checklist").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasStaffUser").value(true))
                .andExpect(jsonPath("$.data.hasVenue").value(false));

        // 建会场 + 档位（停用中的不算——录入前置要求启用）
        jdbcTemplate.update("INSERT INTO auction_venue(code, name, enabled) VALUES ('HT', '飛騨古民具市', 0)");
        mockMvc.perform(get("/api/checklist").session(session))
                .andExpect(jsonPath("$.data.hasVenue").value(false));
        jdbcTemplate.update("UPDATE auction_venue SET enabled = 1");
        jdbcTemplate.update("INSERT INTO price_band(code, lower_bound, upper_bound, enabled) VALUES ('X', 0, 3000, 1)");
        mockMvc.perform(get("/api/checklist").session(session))
                .andExpect(jsonPath("$.data.hasVenue").value(true))
                .andExpect(jsonPath("$.data.hasPriceBand").value(true))
                .andExpect(jsonPath("$.data.hasItem").value(false));

        // 试录一件 → hasItem 置真
        createOneItem(session);
        mockMvc.perform(get("/api/checklist").session(session))
                .andExpect(jsonPath("$.data.hasItem").value(true))
                .andExpect(jsonPath("$.data.printDone").value(false));
    }

    @Test
    void printDone_isIdempotentAndReturnsFreshState() throws Exception {
        MockHttpSession session = loginAs("boss");
        createOneItem(session);

        mockMvc.perform(post("/api/checklist/print-done").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.printDone").value(true));

        // 重复标记 → 幂等（值未变不写，updated_by/updated_at 不漂移）
        Long before = jdbcTemplate.queryForObject(
                "SELECT updated_by FROM sys_setting WHERE `key` = 'checklist.print_done'", Long.class);
        mockMvc.perform(post("/api/checklist/print-done").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.printDone").value(true));
        Long after = jdbcTemplate.queryForObject(
                "SELECT updated_by FROM sys_setting WHERE `key` = 'checklist.print_done'", Long.class);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void checklist_rejectedForEditor() throws Exception {
        MockHttpSession session = loginAs("eichi");
        mockMvc.perform(get("/api/checklist").session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/checklist/print-done").session(session))
                .andExpect(status().isForbidden());
    }
}
