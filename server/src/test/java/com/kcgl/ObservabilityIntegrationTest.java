package com.kcgl;

import com.kcgl.common.obs.AlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 可观测性地基集成测试（M1）：traceId 贯穿 / errorId=traceId 闭环 / 前端错误上报 / sys_alert 去重告警。
 * 契约（docs/01 9.3）：响应头 X-Trace-Id 每请求唯一；500 时响应体 errorId 与该头一致（用户报 ID→
 * 日志按 ID 检索）；client-error 上报限会话+日上限+服务端截断；sys_alert 同 dedupKey 只留最新开启态。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Import(ObservabilityIntegrationTest.ErrorBoomConfiguration.class)
@Testcontainers
class ObservabilityIntegrationTest {

    /** 仅存在于测试上下文的 500 制造端点：验证 errorId 与 traceId 的闭环（生产无此路径）。 */
    @TestConfiguration
    @RestController
    static class ErrorBoomConfiguration {
        @GetMapping("/api/test/boom")
        public String boom() {
            throw new IllegalStateException("boom-for-test");
        }
    }

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String ADMIN_PWD = "Admin-1234-z";
    static final String USER_PWD = "User-1234-y";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    AlertService alertService;

    @BeforeEach
    void resetUsers() {
        jdbcTemplate.update("DELETE FROM sys_user");
        jdbcTemplate.update("DELETE FROM client_error");
        jdbcTemplate.update("DELETE FROM sys_alert");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('boss', ?, '管理者', 1, 1, 0)
                """, ENCODER.encode(ADMIN_PWD));
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('viewer', ?, '閲覧者', 3, 1, 0)
                """, ENCODER.encode(USER_PWD));
    }

    private MockHttpSession loginAs(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .param("username", username).param("password", password))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("登录应成功: %s",
                result.getResponse().getContentAsString()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession();
    }

    // ------------------------------------------------------------------ traceId / errorId 闭环

    @Test
    void 每请求生成唯一traceId响应头() throws Exception {
        String trace1 = mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getHeader("X-Trace-Id");
        String trace2 = mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getHeader("X-Trace-Id");
        assertThat(trace1).isNotBlank().matches("^[0-9a-f]{8}$");
        assertThat(trace2).isNotBlank().isNotEqualTo(trace1);
    }

    @Test
    void 未处理异常_响应体errorId等于当次traceId() throws Exception {
        MockHttpSession admin = loginAs("boss", ADMIN_PWD);
        MvcResult result = mockMvc.perform(get("/api/test/boom").session(admin))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorId").isNotEmpty())
                .andReturn();
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        String errorId = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.errorId");
        // 闭环：用户报 errorId == 当次请求 traceId，日志全行按此 ID 检索
        assertThat(errorId).isEqualTo(traceId);
    }

    // ------------------------------------------------------------------ 前端错误上报

    @Test
    void 前端错误上报_落库含会话用户与服务端截断() throws Exception {
        MockHttpSession viewer = loginAs("viewer", USER_PWD);
        String longStack = "x".repeat(60000);
        mockMvc.perform(post("/api/client-errors").session(viewer)
                        .header("User-Agent", "Mozilla/5.0 KcglWebView")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message": "%s", "stack": "%s", "route": "/entry",
                                 "locale": "ja-JP", "appVersion": "0.1.0", "errorId": "abcd1234",
                                 "queuePending": 3, "queueOldestAgeSec": 120}
                                """.formatted("TypeError: null reference".repeat(100), longStack)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        var row = jdbcTemplate.queryForMap("SELECT * FROM client_error ORDER BY id DESC LIMIT 1");
        assertThat(((String) row.get("message")).length()).isEqualTo(1000); // 截断到列宽
        assertThat(((String) row.get("stack")).length()).isLessThanOrEqualTo(50000); // 截断
        assertThat(row.get("ua")).isEqualTo("Mozilla/5.0 KcglWebView");
        assertThat(row.get("error_id")).isEqualTo("abcd1234");
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username='viewer'", Long.class));
    }

    @Test
    void 前端错误上报_未登录401() throws Exception {
        mockMvc.perform(post("/api/client-errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 前端错误上报_超出每用户日上限429() throws Exception {
        MockHttpSession viewer = loginAs("viewer", USER_PWD);
        Long viewerId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username='viewer'", Long.class);
        for (int i = 0; i < 200; i++) {
            jdbcTemplate.update(
                    "INSERT INTO client_error(message, user_id) VALUES (?, ?)", "m" + i, viewerId);
        }
        mockMvc.perform(post("/api/client-errors").session(viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"over limit\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429001));
    }

    @Test
    void 错误上报查询_仅管理员() throws Exception {
        MockHttpSession viewer = loginAs("viewer", USER_PWD);
        mockMvc.perform(get("/api/client-errors").session(viewer))
                .andExpect(status().isForbidden());
        MockHttpSession admin = loginAs("boss", ADMIN_PWD);
        mockMvc.perform(get("/api/client-errors").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ------------------------------------------------------------------ sys_alert

    @Test
    void 告警同dedupKey只留最新开启态() {
        alertService.record("DISK_USAGE", 2, "ディスク使用率 85%", "disk-usage", "{\"pct\":85}");
        alertService.record("DISK_USAGE", 3, "ディスク使用率 92%", "disk-usage", "{\"pct\":92}");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_alert WHERE dedup_key='disk-usage'", Integer.class);
        assertThat(count).isEqualTo(1);
        var row = jdbcTemplate.queryForMap(
                "SELECT level, message, status FROM sys_alert WHERE dedup_key='disk-usage'");
        assertThat(((Number) row.get("level")).intValue()).isEqualTo(3);
        assertThat(row.get("message")).isEqualTo("ディスク使用率 92%");
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(0);
    }

    @Test
    void 告警已读_仅管理员可读可标记() throws Exception {
        alertService.record("BACKUP_STALE", 2, "バックアップが3日間実行されていません", "backup-stale", null);
        Long alertId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_alert WHERE dedup_key='backup-stale'", Long.class);

        MockHttpSession viewer = loginAs("viewer", USER_PWD);
        mockMvc.perform(patch("/api/alerts/" + alertId + "/read").session(viewer))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/alerts").session(viewer))
                .andExpect(status().isForbidden());

        MockHttpSession admin = loginAs("boss", ADMIN_PWD);
        mockMvc.perform(patch("/api/alerts/" + alertId + "/read").session(admin))
                .andExpect(status().isOk());
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM sys_alert WHERE id = " + alertId, Integer.class);
        assertThat(status).isEqualTo(1);
        mockMvc.perform(get("/api/alerts").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].dedupKey").value("backup-stale"));
    }
}
