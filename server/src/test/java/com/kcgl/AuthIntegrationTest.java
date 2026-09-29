package com.kcgl;

import com.kcgl.module.auth.LoginLockService;
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
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证域集成测试（M1）：登录 / 锁定防爆破 / 登出 / me / 改密 / RBAC 首版。
 * 表结构契约见 V1__init.sql sys_user；安全语义见 docs/01 八节
 * （(账号+IP) 维度 5 次错锁 15 分钟、锁定 423+剩余分钟、未知用户名与错密码同响应防枚举）。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class AuthIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String RAW_PWD = "Test-1234-x";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    LoginLockService loginLockService;

    @BeforeEach
    void resetUsers() {
        jdbcTemplate.update("DELETE FROM sys_user");
        loginLockService.reset(); // 内存失败计数跨用例隔离（DB 锁随 DELETE 复位）
    }

    private void insertUser(String username, int role, int enabled) {
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES (?, ?, ?, ?, ?, 0)
                """, username, ENCODER.encode(RAW_PWD), username, role, enabled);
    }

    private MvcResult login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .param("username", username)
                        .param("password", password))
                .andReturn();
    }

    private MockHttpSession loginForSession(String username, String password) throws Exception {
        MvcResult result = login(username, password);
        assertThat(result.getResponse().getStatus()).as("登录应成功: %s", result.getResponse().getContentAsString()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession();
    }

    // ------------------------------------------------------------------ 登录

    @Test
    void login_success_returnsEnvelopeWithUserInfo() throws Exception {
        insertUser("taro", 1, 1);
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "taro").param("password", RAW_PWD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("taro"))
                .andExpect(jsonPath("$.data.displayName").value("taro"))
                .andExpect(jsonPath("$.data.role").value(1))
                .andExpect(jsonPath("$.data.locale").value("ja-JP"));
    }

    @Test
    void login_wrongPassword_401envelope() throws Exception {
        insertUser("taro", 1, 1);
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "taro").param("password", "wrong-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401002))
                .andExpect(jsonPath("$.errorId").doesNotExist());
    }

    @Test
    void login_unknownUsername_sameResponseAsWrongPassword_noEnumeration() throws Exception {
        insertUser("taro", 1, 1);
        String unknown = login("ghost", "whatever-x").getResponse().getContentAsString();
        String wrongPwd = login("taro", "wrong-password").getResponse().getContentAsString();
        // code 与 message 必须一致，不得泄露「用户不存在」
        assertThat(unknown).isEqualTo(wrongPwd);
    }

    @Test
    void login_disabledAccount_rejected401() throws Exception {
        insertUser("sabaku", 1, 0);
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "sabaku").param("password", RAW_PWD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401003));
    }

    // ------------------------------------------------------------------ 锁定防爆破

    @Test
    void login_fiveFailures_sixthCorrectPasswordStillLocked423() throws Exception {
        insertUser("locky", 2, 1);
        for (int i = 0; i < 5; i++) {
            MvcResult r = login("locky", "wrong-password");
            assertThat(r.getResponse().getStatus()).as("第 %d 次失败应为 401", i + 1).isEqualTo(401);
        }
        // 锁定后即使密码正确也 423，并带剩余分钟
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "locky").param("password", RAW_PWD))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value(423001))
                .andExpect(jsonPath("$.data.remainingMinutes").isNumber());
    }

    @Test
    void login_unknownUserFiveFailures_sixthInMemoryLocked423() throws Exception {
        // 撞库探测不存在的账号：无 DB 行可锁，内存锁兜底（同一 (账号+IP) 维度）
        for (int i = 0; i < 5; i++) {
            MvcResult r = login("ghost-user", "wrong-password");
            assertThat(r.getResponse().getStatus()).as("第 %d 次失败应为 401", i + 1).isEqualTo(401);
        }
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "ghost-user").param("password", "whatever-x"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value(423001))
                .andExpect(jsonPath("$.data.remainingMinutes").isNumber());
    }

    @Test
    @org.junit.jupiter.api.Tag("regression")
    void regression_urlEncodedPathVariantWhileLocked_stillBlocked423() throws Exception {
        // 复现：LoginThrottleFilter 曾对 getRequestURI() 做原始字符串比较，
        // URL 编码变体（%6C=l）绕过内存锁直达 BCrypt；Security 过滤链按解码路径匹配，
        // 节流判断必须用同源 PathPatternRequestMatcher（见 docs/04 防复发纪律）。
        // 注意：post(String) 会把 URL 当模板再编码（%6C→%256C 被防火墙拒），
        // 必须用 URI 重载发送原始编码路径——生产容器的防火墙不拒绝 %6C，绕过面真实存在
        for (int i = 0; i < 5; i++) {
            login("ghost-enc", "wrong-password");
        }
        mockMvc.perform(post(java.net.URI.create("/api/auth/%6Cogin"))
                        .param("username", "ghost-enc").param("password", "whatever-x"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value(423001));
    }

    // ------------------------------------------------------------------ CSRF 兜底

    @Test
    void csrf_originNotAllowlisted_403() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .header("Origin", "https://evil.example")
                        .param("username", "taro").param("password", RAW_PWD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403002));
    }

    // ------------------------------------------------------------------ 会话与 me

    @Test
    void me_unauthenticated_401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401001));
    }

    @Test
    void me_afterLogin_returnsCurrentUser() throws Exception {
        insertUser("hanako", 2, 1);
        // id 主键随响应下发（前端 SSE 回声抑制的比对基准，D-070）
        Long hanakoId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'hanako'", Long.class);
        MockHttpSession session = loginForSession("hanako", RAW_PWD);
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(hanakoId))
                .andExpect(jsonPath("$.data.username").value("hanako"))
                .andExpect(jsonPath("$.data.role").value(2));
    }

    @Test
    void logout_invalidatesSession() throws Exception {
        insertUser("hanako", 2, 1);
        MockHttpSession session = loginForSession("hanako", RAW_PWD);
        mockMvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 改密

    @Test
    void changePassword_oldInvalid_newWorks_sessionPreserved() throws Exception {
        insertUser("jiro", 2, 1);
        MockHttpSession session = loginForSession("jiro", RAW_PWD);
        mockMvc.perform(put("/api/auth/me/password").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldPassword": "%s", "newPassword": "New-5678-y"}
                                """.formatted(RAW_PWD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        // 会话不强制失效
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk());
        // 旧密码拒绝、新密码可登录
        assertThat(login("jiro", RAW_PWD).getResponse().getStatus()).isEqualTo(401);
        assertThat(login("jiro", "New-5678-y").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void changePassword_newPasswordTooShort_400() throws Exception {
        insertUser("jiro", 2, 1);
        MockHttpSession session = loginForSession("jiro", RAW_PWD);
        mockMvc.perform(put("/api/auth/me/password").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldPassword": "%s", "newPassword": "short"}
                                """.formatted(RAW_PWD)))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ RBAC 首版

    @Test
    void listUsers_byViewer_403() throws Exception {
        insertUser("viewer", 3, 1);
        MockHttpSession session = loginForSession("viewer", RAW_PWD);
        mockMvc.perform(get("/api/users").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403001));
    }

    // ------------------------------------------------------------------ 全局契约

    @Test
    @org.junit.jupiter.api.Tag("regression")
    void regression_unknownPath_404envelopeNot500() throws Exception {
        // 复现：onUnhandled(Exception.class) 曾把无 handler 路径的 NoResourceFoundException
        // 吞成 500+errorId——打错的 URL 全变系统错误并污染 ERROR 日志（UserIntegrationTest RED 阶段逮住）。
        // 必须登录后访问：未认证请求在 authorizeHttpRequests 就 401，到不了 handler 层
        insertUser("taro", 1, 1);
        MockHttpSession session = loginForSession("taro", RAW_PWD);
        mockMvc.perform(get("/api/no-such-path").session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404001))
                .andExpect(jsonPath("$.errorId").doesNotExist());
    }
}
