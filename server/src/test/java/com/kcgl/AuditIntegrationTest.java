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
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 审计闭环集成测试（M1）：账号管理六端点 + 自助改密/改语言 → operation_log 落一行。
 * 契约（docs/01 八节）：审计与业务同事务（原子）；detail 含操作对象标识但绝不含密码材料；
 * operator 为操作人快照（id+username）；ip/ua 从请求取；表只增不改不删（无任何改删端点）。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class AuditIntegrationTest {

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
    ObjectMapper objectMapper;
    /** 按名注入：actuator 的 controllerEndpointHandlerMapping 也是同类型，@Autowired 按类型会歧义 */
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @BeforeEach
    void resetUsers() {
        jdbcTemplate.update("DELETE FROM sys_user");
        jdbcTemplate.update("DELETE FROM operation_log");
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

    private Map<String, Object> lastLog() {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM operation_log ORDER BY id DESC LIMIT 1");
    }

    private JsonNode detail(Map<String, Object> log) throws Exception {
        String json = (String) log.get("detail");
        return objectMapper.readTree(json);
    }

    // ------------------------------------------------------------------ 管理端操作审计

    @Test
    void 建号_审计USER_CREATE含操作人快照与IP_UA() throws Exception {
        MockHttpSession admin = loginAs("boss", ADMIN_PWD);
        mockMvc.perform(post("/api/users").session(admin)
                        .header("User-Agent", "KcglTestAgent/1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "shain1", "displayName": "社員一郎", "role": 2,
                                 "locale": "ja-JP", "password": "Init-5678-a"}
                                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        Map<String, Object> log = lastLog();
        assertThat(log.get("action")).isEqualTo("USER_CREATE");
        assertThat(log.get("entity_type")).isEqualTo("sys_user");
        assertThat(log.get("operator_name")).isEqualTo("boss");
        // Connector/J 8 的 queryForMap 把 BIGINT 读成 BigInteger，统一走 Number 比较
        assertThat(((Number) log.get("operator_id")).longValue()).isEqualTo(jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username='boss'", Long.class));
        assertThat((String) log.get("ip")).isNotBlank();
        assertThat((String) log.get("ua")).isEqualTo("KcglTestAgent/1.0");
        // detail 含操作对象标识
        assertThat(detail(log).get("username").asString()).isEqualTo("shain1");
    }

    @Test
    void 改号_审计含前后值diff() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('taro', ?, '太郎', 3, 1, 0)
                """, ENCODER.encode(USER_PWD));
        Long id = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username='taro'", Long.class);
        MockHttpSession admin = loginAs("boss", ADMIN_PWD);
        mockMvc.perform(put("/api/users/" + id).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "太郎・改", "role": 2, "locale": "ja-JP"}
                                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        Map<String, Object> log = lastLog();
        assertThat(log.get("action")).isEqualTo("USER_UPDATE");
        assertThat(detail(log).get("before").get("role").asInt()).isEqualTo(3);
        assertThat(detail(log).get("after").get("role").asInt()).isEqualTo(2);
        assertThat(detail(log).get("before").get("displayName").asString()).isEqualTo("太郎");
        assertThat(detail(log).get("after").get("displayName").asString()).isEqualTo("太郎・改");
    }

    @Test
    void 停用与解锁_各审计一行() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('locky', ?, 'ロッキー', 2, 1, 0)
                """, ENCODER.encode(USER_PWD));
        Long id = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username='locky'", Long.class);
        MockHttpSession admin = loginAs("boss", ADMIN_PWD);

        mockMvc.perform(patch("/api/users/" + id + "/status").session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\": 0}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(lastLog().get("action")).isEqualTo("USER_STATUS");
        assertThat(detail(lastLog()).get("enabled").asInt()).isEqualTo(0);

        mockMvc.perform(patch("/api/users/" + id + "/unlock").session(admin))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(lastLog().get("action")).isEqualTo("USER_UNLOCK");
    }

    @Test
    void 重置密码_审计行绝不含任何密码材料() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('jiro', ?, '次郎', 3, 1, 0)
                """, ENCODER.encode(USER_PWD));
        Long id = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username='jiro'", Long.class);
        MockHttpSession admin = loginAs("boss", ADMIN_PWD);
        MvcResult reset = mockMvc.perform(post("/api/users/" + id + "/password-reset").session(admin))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        String initialPassword = com.jayway.jsonpath.JsonPath.read(
                reset.getResponse().getContentAsString(), "$.data.initialPassword");

        Map<String, Object> log = lastLog();
        assertThat(log.get("action")).isEqualTo("USER_PASSWORD_RESET");
        // detail 与整行日志中不得出现初始密码或任何哈希
        String wholeRow = log.toString();
        assertThat(wholeRow).doesNotContain(initialPassword);
        assertThat(wholeRow).doesNotContain("$2a$");
        assertThat(detail(log).get("username").asString()).isEqualTo("jiro");
    }

    // ------------------------------------------------------------------ 自助操作审计

    @Test
    void 自助改密_审计CHANGE_PASSWORD() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('hanako', ?, '花子', 2, 1, 0)
                """, ENCODER.encode(USER_PWD));
        MockHttpSession session = loginAs("hanako", USER_PWD);
        mockMvc.perform(put("/api/auth/me/password").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldPassword": "%s", "newPassword": "New-5678-y"}
                                """.formatted(USER_PWD)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        Map<String, Object> log = lastLog();
        assertThat(log.get("action")).isEqualTo("CHANGE_PASSWORD");
        assertThat(log.get("operator_name")).isEqualTo("hanako");
        assertThat(log.toString()).doesNotContain("New-5678-y");
    }

    @Test
    void 自助改语言_审计CHANGE_LOCALE() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('hanako', ?, '花子', 2, 1, 0)
                """, ENCODER.encode(USER_PWD));
        MockHttpSession session = loginAs("hanako", USER_PWD);
        mockMvc.perform(put("/api/auth/me/locale").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\": \"en-US\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        Map<String, Object> log = lastLog();
        assertThat(log.get("action")).isEqualTo("CHANGE_LOCALE");
        assertThat(detail(log).get("locale").asString()).isEqualTo("en-US");
    }

    // ------------------------------------------------------------------ 不可变性

    @Test
    void operation_log无任何改删端点_表只增() {
        // 结构性保证：审计表无 UPDATE/DELETE API 路由；本用例固化该契约——
        // 任何审计相关端点只能是只读查询，写路径唯一为业务事务内同步 INSERT
        List<String> routes = handlerMapping.getHandlerMethods().keySet().stream()
                .map(Object::toString).toList();
        assertThat(routes).noneMatch(r -> r.contains("operation-log") && (r.contains("PUT") || r.contains("DELETE")));
    }
}
