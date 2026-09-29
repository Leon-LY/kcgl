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
 * 字典后台集成测试（M2-1，docs/01 六节 dict 模块）：
 * - 角色矩阵：查询全员 / 会场创建改名=可编辑+ / 会场停用·年代号·档位=管理员
 * - 唯一性与重叠：会场码/档位码/年份代号双向唯一；档位区间重叠 409004（相邻不算重叠）
 * - match 左闭右开语义；无命中 404002 引导后台配置
 * - 审计：每写操作 operation_log 一行（entity_id 精确断言）
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class DictIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String PASSWORD = "Dict-1234-x";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetFixtures() {
        // 显式删三个测试账号再插（幂等）：bootstrap 随机管理员留在库里不影响断言
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN ('boss','eichi','miru')");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('boss', ?, '管理者', 1, 1, 0), ('eichi', ?, '編集者', 2, 1, 0), ('miru', ?, '閲覧者', 3, 1, 0)
                """, ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD));
        jdbcTemplate.update("DELETE FROM auction_venue");
        jdbcTemplate.update("DELETE FROM price_band");
    }

    private MockHttpSession loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .param("username", username).param("password", PASSWORD))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("登录应成功: %s",
                result.getResponse().getContentAsString()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession();
    }

    private long auditCount(String action, Long entityId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = ? AND entity_id = ?",
                Long.class, action, entityId);
    }

    private long extractId(String json) {
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    // ------------------------------------------------------------------ 会场

    @Test
    void listVenues_byViewer_returnsEmptyList() throws Exception {
        MockHttpSession viewer = loginAs("miru");
        mockMvc.perform(get("/api/venues").session(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void createVenue_byEditor_writesAuditLog() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        String body = mockMvc.perform(post("/api/venues").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"HT","name":"飛騨古民具市"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("HT"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(auditCount("VENUE_CREATE", extractId(body))).isEqualTo(1);
    }

    @Test
    void createVenue_byViewer_403() throws Exception {
        MockHttpSession viewer = loginAs("miru");
        mockMvc.perform(post("/api/venues").session(viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"HT","name":"飛騨古民具市"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403001));
    }

    @Test
    void createVenue_duplicateCode_409002() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        mockMvc.perform(post("/api/venues").session(editor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"HT","name":"飛騨古民具市"}
                        """)).andExpect(status().isOk());
        mockMvc.perform(post("/api/venues").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"HT","name":"別の会場"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409002));
    }

    @Test
    void createVenue_lowercaseCode_400() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        mockMvc.perform(post("/api/venues").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"ht","name":"小文字"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));
    }

    @Test
    void updateVenue_editorRename_adminDisable_enabledFilter() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        MockHttpSession admin = loginAs("boss");
        String body = mockMvc.perform(post("/api/venues").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"NG","name":"名古屋市"}
                                """))
                .andReturn().getResponse().getContentAsString();
        long id = extractId(body);

        mockMvc.perform(put("/api/venues/" + id).session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"名古屋市大須"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("名古屋市大須"));
        assertThat(auditCount("VENUE_RENAME", id)).isEqualTo(1);

        // 停用是管理员专属
        mockMvc.perform(patch("/api/venues/" + id + "/status").session(editor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":0}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/venues/" + id + "/status").session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
        assertThat(auditCount("VENUE_STATUS", id)).isEqualTo(1);

        // 再建一个启用会场：无过滤见 2，enabled=true 只见 1
        mockMvc.perform(post("/api/venues").session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"FK","name":"福岡市"}
                        """)).andExpect(status().isOk());
        MockHttpSession viewer = loginAs("miru");
        mockMvc.perform(get("/api/venues").session(viewer))
                .andExpect(jsonPath("$.data.length()").value(2));
        mockMvc.perform(get("/api/venues").session(viewer).param("enabled", "true"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("FK"));
    }

    // ------------------------------------------------------------------ 档位

    @Test
    void createPriceBands_byAdmin_sortedByLowerBound_firstOpenEnded() throws Exception {
        MockHttpSession admin = loginAs("boss");
        mockMvc.perform(post("/api/price-bands").session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"B","lowerBound":1000,"upperBound":3000}
                        """)).andExpect(status().isOk());
        mockMvc.perform(post("/api/price-bands").session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"C","lowerBound":3000,"upperBound":null}
                        """)).andExpect(status().isOk());
        mockMvc.perform(post("/api/price-bands").session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"A","lowerBound":null,"upperBound":1000}
                        """)).andExpect(status().isOk());

        mockMvc.perform(get("/api/price-bands").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].code").value("A"))
                .andExpect(jsonPath("$.data[0].lowerBound").doesNotExist())
                .andExpect(jsonPath("$.data[1].code").value("B"))
                .andExpect(jsonPath("$.data[2].code").value("C"));
    }

    @Test
    void createPriceBand_overlappingRange_409004_adjacentAllowed() throws Exception {
        MockHttpSession admin = loginAs("boss");
        mockMvc.perform(post("/api/price-bands").session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"A","lowerBound":null,"upperBound":1000}
                        """)).andExpect(status().isOk());
        // 相邻：上界==下界，左闭右开不交
        mockMvc.perform(post("/api/price-bands").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"B","lowerBound":1000,"upperBound":3000}
                                """))
                .andExpect(status().isOk());
        // 严格重叠：[2000,4000) 与 [1000,3000) 相交
        mockMvc.perform(post("/api/price-bands").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"C","lowerBound":2000,"upperBound":4000}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409004));
        // 内含也拒绝：[1200,1500)
        mockMvc.perform(post("/api/price-bands").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"D","lowerBound":1200,"upperBound":1500}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409004));
    }

    @Test
    void createPriceBand_invertedRange_400_duplicateCode_409003_byEditor_403() throws Exception {
        MockHttpSession admin = loginAs("boss");
        MockHttpSession editor = loginAs("eichi");
        mockMvc.perform(post("/api/price-bands").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"A","lowerBound":3000,"upperBound":1000}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        mockMvc.perform(post("/api/price-bands").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"B","lowerBound":1000,"upperBound":3000}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/price-bands").session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"B","lowerBound":1000,"upperBound":3000}
                        """)).andExpect(status().isOk());
        mockMvc.perform(post("/api/price-bands").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"B","lowerBound":5000,"upperBound":null}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409003));
    }

    @Test
    void matchPriceBand_leftClosedRightOpen_gapOrDisabled_404002() throws Exception {
        MockHttpSession admin = loginAs("boss");
        MockHttpSession viewer = loginAs("miru");
        // 只配 B 档 [1000,3000)：500 落在空档
        mockMvc.perform(post("/api/price-bands").session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"B","lowerBound":1000,"upperBound":3000}
                        """)).andExpect(status().isOk());
        mockMvc.perform(get("/api/price-bands/match").session(viewer).param("price", "500"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404002));

        // 边界：1000 命中（左闭）、2999 命中、3000 不命中（右开）
        mockMvc.perform(get("/api/price-bands/match").session(viewer).param("price", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("B"));
        mockMvc.perform(get("/api/price-bands/match").session(viewer).param("price", "2999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("B"));
        mockMvc.perform(get("/api/price-bands/match").session(viewer).param("price", "3000"))
                .andExpect(status().isNotFound());

        // 停用 B 后同价不再命中
        Long id = jdbcTemplate.queryForObject("SELECT id FROM price_band WHERE code = 'B'", Long.class);
        mockMvc.perform(patch("/api/price-bands/" + id + "/status").session(admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
        mockMvc.perform(get("/api/price-bands/match").session(viewer).param("price", "1000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404002));
        assertThat(auditCount("BAND_STATUS", id)).isEqualTo(1);
    }

    @Test
    void updatePriceBand_byAdmin_overlapRejected_selfAllowed_audited() throws Exception {
        MockHttpSession admin = loginAs("boss");
        String body = mockMvc.perform(post("/api/price-bands").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"A","lowerBound":null,"upperBound":1000}
                                """))
                .andReturn().getResponse().getContentAsString();
        long id = extractId(body);
        mockMvc.perform(post("/api/price-bands").session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"B","lowerBound":1000,"upperBound":3000}
                        """)).andExpect(status().isOk());

        // 扩 A 上界到 1500 → 与 B 重叠，拒
        mockMvc.perform(put("/api/price-bands/" + id).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"A","lowerBound":null,"upperBound":1500}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409004));
        // 自身区间原样重写不误判
        mockMvc.perform(put("/api/price-bands/" + id).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"A","lowerBound":null,"upperBound":1000}
                                """))
                .andExpect(status().isOk());
        assertThat(auditCount("BAND_UPDATE", id)).isEqualTo(1);
    }
}
