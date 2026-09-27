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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账号管理集成测试（M1）：管理员 CRUD / 停用即时踢出 / 手动解锁 / 重置密码。
 * 语义契约（docs/01 六节 users 模块 + D-024）：
 * - 停用必须在既有会话的下一请求立即生效（不能等 12h 会话过期）
 * - 解锁 = 清 locked_until + failed_attempts，立即恢复可登录
 * - 重置密码 = 响应体一次性返回初始密码，旧密码立即失效
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class UserIntegrationTest {

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

    @BeforeEach
    void resetUsers() {
        jdbcTemplate.update("DELETE FROM sys_user");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('boss', ?, '管理者', 1, 1, 0)
                """, ENCODER.encode(ADMIN_PWD));
    }

    private MockHttpSession loginAs(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .param("username", username).param("password", password))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("登录应成功: %s",
                result.getResponse().getContentAsString()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession();
    }

    private Long insertUser(String username, int role) {
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES (?, ?, ?, ?, 1, 0)
                """, username, ENCODER.encode(USER_PWD), username, role);
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    // ------------------------------------------------------------------ 列表与创建

    @Test
    void 管理员查账号列表_含锁定与停用状态() throws Exception {
        insertUser("taro", 2);
        jdbcTemplate.update("UPDATE sys_user SET locked_until = '2099-01-01 00:00:00' WHERE username = 'taro'");
        MockHttpSession admin = loginAs("boss", ADMIN_PWD);
        mockMvc.perform(get("/api/users").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list[?(@.username=='taro')].locked").value(true));
    }

    @Test
    void 管理员创建账号_新账号立即可登录() throws Exception {
        MockHttpSession admin = loginAs("boss", ADMIN_PWD);
        mockMvc.perform(post("/api/users").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "shain1", "displayName": "社員一郎", "role": 2,
                                 "locale": "ja-JP", "password": "Init-5678-a"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("shain1"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        // 创建返回的初始密码可直接登录
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .param("username", "shain1").param("password", "Init-5678-a")).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 创建重名账号_409() throws Exception {
        MockHttpSession admin = loginAs("boss", ADMIN_PWD);
        mockMvc.perform(post("/api/users").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "boss", "displayName": "重复", "role": 3,
                                 "locale": "ja-JP", "password": "Init-5678-b"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409001));
    }

    @Test
    void 可编辑角色访问账号管理_403() throws Exception {
        insertUser("editor", 2);
        MockHttpSession editor = loginAs("editor", USER_PWD);
        mockMvc.perform(post("/api/users").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "x", "displayName": "x", "role": 3,
                                 "locale": "ja-JP", "password": "Init-5678-c"}
                                """))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 更新与停用

    @Test
    void 管理员更新账号_displayName与角色生效() throws Exception {
        Long id = insertUser("taro", 3);
        MockHttpSession admin = loginAs("boss", ADMIN_PWD);
        mockMvc.perform(put("/api/users/" + id).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "太郎・改", "role": 2, "locale": "en-US"}
                                """))
                .andExpect(status().isOk());
        // 变更对下次登录生效
        MockHttpSession relogin = loginAs("taro", USER_PWD);
        mockMvc.perform(get("/api/auth/me").session(relogin))
                .andExpect(jsonPath("$.data.displayName").value("太郎・改"))
                .andExpect(jsonPath("$.data.role").value(2));
    }

    @Test
    void 停用账号_既有会话下一请求立即401() throws Exception {
        insertUser("hanako", 2);
        MockHttpSession victim = loginAs("hanako", USER_PWD);
        // 登录后停用
        Long id = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = 'hanako'", Long.class);
        MockHttpSession admin = loginAs("boss", ADMIN_PWD);
        mockMvc.perform(patch("/api/users/" + id + "/status").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": 0}"))
                .andExpect(status().isOk());
        // 受害者既有会话立即失效（不能等 12h 过期）
        mockMvc.perform(get("/api/auth/me").session(victim))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 管理员不能停用自己_防自锁() throws Exception {
        MockHttpSession admin = loginAs("boss", ADMIN_PWD);
        Long adminId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = 'boss'", Long.class);
        mockMvc.perform(patch("/api/users/" + adminId + "/status").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
        // 管理员仍在会话中（未被停用）
        mockMvc.perform(get("/api/auth/me").session(admin))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 解锁与重置密码

    @Test
    void 管理员解锁被锁账号_正确密码立即恢复登录() throws Exception {
        Long id = insertUser("locky", 2);
        jdbcTemplate.update("UPDATE sys_user SET locked_until = '2099-01-01 00:00:00', "
                + "failed_attempts = 5 WHERE id = " + id);
        // 锁定状态下登录被拒
        MvcResult locked = mockMvc.perform(post("/api/auth/login")
                .param("username", "locky").param("password", USER_PWD)).andReturn();
        assertThat(locked.getResponse().getStatus()).isEqualTo(423);
        // 管理员解锁
        MockHttpSession admin = loginAs("boss", ADMIN_PWD);
        mockMvc.perform(patch("/api/users/" + id + "/unlock").session(admin))
                .andExpect(status().isOk());
        // 正确密码立即恢复
        MvcResult ok = mockMvc.perform(post("/api/auth/login")
                .param("username", "locky").param("password", USER_PWD)).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 管理员重置密码_旧失效新可登录且须首登改密() throws Exception {
        insertUser("jiro", 3);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = 'jiro'", Long.class);
        MockHttpSession admin = loginAs("boss", ADMIN_PWD);
        MvcResult reset = mockMvc.perform(post("/api/users/" + id + "/password-reset").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.initialPassword").isNotEmpty())
                .andReturn();
        String initialPassword = com.jayway.jsonpath.JsonPath.read(
                reset.getResponse().getContentAsString(), "$.data.initialPassword");
        // 旧密码失效
        MvcResult old = mockMvc.perform(post("/api/auth/login")
                .param("username", "jiro").param("password", USER_PWD)).andReturn();
        assertThat(old.getResponse().getStatus()).isEqualTo(401);
        // 新密码可登录，且 mustChangePwd=true
        MvcResult fresh = mockMvc.perform(post("/api/auth/login")
                .param("username", "jiro").param("password", initialPassword)).andReturn();
        assertThat(fresh.getResponse().getContentAsString()).contains("\"mustChangePwd\":true");
    }
}
