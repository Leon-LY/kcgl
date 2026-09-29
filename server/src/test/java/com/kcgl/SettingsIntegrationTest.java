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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 系统设置集成测试（M5-③）：GET 快照未落库回退默认（30/90/small/38×21 预置面）、
 * PUT 单键管理员校验（范围/格式/黄红跨字段关系——对照另一侧现值）、未知键 404、
 * 同值幂等（不写库不写审计，updated_by/updated_at 不漂移）、审计行内容（D-026）。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class SettingsIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String PASSWORD = "Set-1234-s";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    ObjectMapper objectMapper;

    long bossId;

    @BeforeEach
    void resetToFreshInstall() {
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM sys_setting");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN ('boss','eichi','miru')");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('boss', ?, '管理者', 1, 1, 0), ('eichi', ?, '編集者', 2, 1, 0), ('miru', ?, '閲覧者', 3, 1, 0)
                """, ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD));
        bossId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = 'boss'", Long.class);
    }

    private MockHttpSession loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .param("username", username).param("password", PASSWORD))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("登录应成功: %s",
                result.getResponse().getContentAsString()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession();
    }

    private ResultActions putSetting(MockHttpSession session, String key, String value) throws Exception {
        return mockMvc.perform(put("/api/settings/{key}", key).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"" + value + "\"}"));
    }

    @Test
    void settings_defaultsOnFreshInstall_readableByAllRoles() throws Exception {
        // 未落库 → 快照回退默认（warn 30 / alarm 90 / small / 50×30）
        mockMvc.perform(get("/api/settings").session(loginAs("boss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnDays").value(30))
                .andExpect(jsonPath("$.data.alarmDays").value(90))
                .andExpect(jsonPath("$.data.labelPreset").value("small"))
                .andExpect(jsonPath("$.data.labelWidthMm").value(50))
                .andExpect(jsonPath("$.data.labelHeightMm").value(30));
        // GET 全员（打印页读标签尺寸、列表读滞销阈值）
        mockMvc.perform(get("/api/settings").session(loginAs("eichi")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnDays").value(30));
        mockMvc.perform(get("/api/settings").session(loginAs("miru")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alarmDays").value(90));
    }

    @Test
    void put_adminUpdatesValue_returnsTrimmedFreshSnapshot_andPersists() throws Exception {
        MockHttpSession admin = loginAs("boss");

        // 值带空白 → trim 后生效；响应即最新快照（操作者自己的页面由 PUT 响应驱动）
        putSetting(admin, "slow_move.warn_days", " 45 ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnDays").value(45))
                .andExpect(jsonPath("$.data.alarmDays").value(90));

        putSetting(admin, "label.preset", "custom")
                .andExpect(jsonPath("$.data.labelPreset").value("custom"));
        putSetting(admin, "label.width", "70")
                .andExpect(jsonPath("$.data.labelWidthMm").value(70));
        putSetting(admin, "label.height", "25")
                .andExpect(jsonPath("$.data.labelHeightMm").value(25));

        // 落库核对：trim 后的值 + 操作人（BIGINT UNSIGNED 经 queryForMap 回来是 BigInteger，
        // 取类型化单值断言，规避驱动映射类型差异）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT `value` FROM sys_setting WHERE `key` = 'slow_move.warn_days'", String.class))
                .isEqualTo("45");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT updated_by FROM sys_setting WHERE `key` = 'slow_move.warn_days'", Long.class))
                .isEqualTo(bossId);

        // 后续 GET 读到同值（读侧不再回退默认）
        mockMvc.perform(get("/api/settings").session(admin))
                .andExpect(jsonPath("$.data.warnDays").value(45))
                .andExpect(jsonPath("$.data.labelPreset").value("custom"))
                .andExpect(jsonPath("$.data.labelWidthMm").value(70))
                .andExpect(jsonPath("$.data.labelHeightMm").value(25));
    }

    @Test
    void put_rejectedForNonAdmin() throws Exception {
        mockMvc.perform(put("/api/settings/slow_move.warn_days").session(loginAs("eichi"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"45\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/settings/label.width").session(loginAs("miru"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"70\"}"))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_setting WHERE `key` IN ('slow_move.warn_days','label.width')", Integer.class))
                .isZero();
    }

    @Test
    void put_unknownKey_notFound() throws Exception {
        putSetting(loginAs("boss"), "no.such.key", "1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404001));
    }

    @Test
    void put_invalidValues_rejectedWithoutWrite() throws Exception {
        MockHttpSession admin = loginAs("boss");

        // 天数：非数字 / 越下界 / 越上界（1~3650）
        putSetting(admin, "slow_move.warn_days", "abc").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400017));
        putSetting(admin, "slow_move.warn_days", "0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400017));
        putSetting(admin, "slow_move.alarm_days", "4000").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400017));
        // 预置面枚举
        putSetting(admin, "label.preset", "huge").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400017));
        // 幅 30~100：越界 + 非数字
        putSetting(admin, "label.width", "29").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400017));
        putSetting(admin, "label.width", "101").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400017));
        putSetting(admin, "label.width", "xx").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400017));
        // 高さ 21~60：越界
        putSetting(admin, "label.height", "20").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400017));
        putSetting(admin, "label.height", "61").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400017));

        // 拒绝即不落库
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_setting", Integer.class)).isZero();
    }

    @Test
    void put_enforcesCrossFieldThresholdRelationAgainstCurrentValue() throws Exception {
        MockHttpSession admin = loginAs("boss");

        // 默认 warn=30：alarm 压到 25（≤warn）→ 拒
        putSetting(admin, "slow_move.alarm_days", "25").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400017));

        // alarm 抬到 40（>30）→ 过；此后 warn 不得 ≥40
        putSetting(admin, "slow_move.alarm_days", "40").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alarmDays").value(40));
        putSetting(admin, "slow_move.warn_days", "45").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400017));
        putSetting(admin, "slow_move.warn_days", "40").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400017));
        putSetting(admin, "slow_move.warn_days", "35").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnDays").value(35))
                .andExpect(jsonPath("$.data.alarmDays").value(40));

        // 校验拒绝不改现值
        var row = jdbcTemplate.queryForMap(
                "SELECT `value` FROM sys_setting WHERE `key` = 'slow_move.warn_days'");
        assertThat(row.get("value")).isEqualTo("35");
    }

    @Test
    void put_sameValue_isIdempotent_noRewriteNoExtraAudit() throws Exception {
        MockHttpSession admin = loginAs("boss");
        putSetting(admin, "slow_move.warn_days", "45").andExpect(status().isOk());

        var before = jdbcTemplate.queryForMap(
                "SELECT `value`, updated_by, updated_at FROM sys_setting WHERE `key` = 'slow_move.warn_days'");
        Integer audits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'SETTING_UPDATE'", Integer.class);
        assertThat(audits).isEqualTo(1);

        // 同值再 PUT → 放行返回快照，但不写库、不写审计
        putSetting(admin, "slow_move.warn_days", "45").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnDays").value(45));

        var after = jdbcTemplate.queryForMap(
                "SELECT `value`, updated_by, updated_at FROM sys_setting WHERE `key` = 'slow_move.warn_days'");
        assertThat(after).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'SETTING_UPDATE'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void put_changedValue_writesAuditRowWithOldNewDetail() throws Exception {
        MockHttpSession admin = loginAs("boss");
        putSetting(admin, "label.width", "60").andExpect(status().isOk());

        var row = jdbcTemplate.queryForMap("""
                SELECT entity_type, detail, operator_name FROM operation_log
                WHERE action = 'SETTING_UPDATE'
                """);
        assertThat(row.get("entity_type")).isEqualTo("sys_setting");
        assertThat(row.get("operator_name")).isEqualTo("boss");
        // detail 为 JSON 文本（Jackson 3 默认输出带空格分隔——readTree 断言不绑序列化间距）
        JsonNode detail = objectMapper.readTree((String) row.get("detail"));
        assertThat(detail.get("key").asString()).isEqualTo("label.width");
        assertThat(detail.get("oldValue").asString()).isEmpty(); // 未落库 → oldValue=""
        assertThat(detail.get("newValue").asString()).isEqualTo("60");
    }
}
