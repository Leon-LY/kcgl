package com.kcgl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * システム状況 + 诊断导出集成测试（M5-④，docs/01 9.3）：白名单字段齐备、
 * 各计数口径（号引擎/批次状态/近 7 日含今日/开启告警）与落库数据对账；
 * 管理员专用（编辑/查看 403）；导出为 JSON 附件（Content-Disposition）。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class SystemStatusIntegrationTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String PASSWORD = "Sts-1234-t";

    /** backup-status.json 桩：路径静态注册，内容随用例改写（reader 每次现场重读）。 */
    static final Path BACKUP_STATUS_FILE = createStatusFile();

    static Path createStatusFile() {
        try {
            return Files.createTempFile("kcgl-backup-status", ".json");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void backupStatusProperty(DynamicPropertyRegistry registry) {
        registry.add("kcgl.backup.status-path", () -> BACKUP_STATUS_FILE.toString());
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    ObjectMapper objectMapper;

    long bossId;

    @BeforeEach
    void resetFixtures() {
        jdbcTemplate.update("DELETE FROM sys_alert");
        jdbcTemplate.update("DELETE FROM client_error");
        jdbcTemplate.update("DELETE FROM excel_import_batch");
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM stock_ledger");
        jdbcTemplate.update("DELETE FROM item");
        jdbcTemplate.update("DELETE FROM seq_item_code");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN ('boss','eichi')");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('boss', ?, '管理者', 1, 1, 0), ('eichi', ?, '編集者', 2, 1, 0)
                """, ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD));
        bossId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = 'boss'", Long.class);
        jdbcTemplate.update("DELETE FROM auction_venue");
        jdbcTemplate.update("DELETE FROM price_band");
        jdbcTemplate.update(
                "INSERT INTO price_band(code, lower_bound, upper_bound, enabled) VALUES ('X', 0, 3000, 1)");
        jdbcTemplate.update(
                "INSERT INTO auction_venue(code, name, enabled) VALUES ('HT', '飛騨古民具市', 1)");

        // 数据水位：1 商品 + 1 流水 + 4 行操作日志（号引擎 2 发 1 跳）
        jdbcTemplate.update("""
                INSERT INTO item(item_code, venue_id, venue_code, buy_month, seq_prefix, seq_no,
                  buy_date, purchase_price, price_band_code, warehouse, stock_status, sale_status,
                  voided, deleted, created_by)
                SELECT 'HTK5-A1X', id, 'HT', 5, 'A', 1, '2026-05-10', 1000, 'X', 1, 1, 0, 0, 0, ?
                FROM auction_venue WHERE code = 'HT'
                """, bossId);
        long itemId = jdbcTemplate.queryForObject(
                "SELECT id FROM item WHERE item_code = 'HTK5-A1X'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO stock_ledger(client_req_id, txn_type, item_id, item_code, qty_change,
                  operator_id, operator_name)
                VALUES ('r-1', 1, ?, 'HTK5-A1X', 1, ?, '管理者')
                """, itemId, bossId);
        insertLog("ITEM_CODE", itemId);
        insertLog("ITEM_CODE", itemId);
        insertLog("ITEM_CODE_SKIP", itemId);
        insertLog("ITEM_VOID", itemId);

        // Excel 批次：1 完成 + 1 失败（id 更大 → recent 首行）
        jdbcTemplate.update("""
                INSERT INTO excel_import_batch(file_sha256, original_filename, status, row_count,
                  error_count, uploaded_by, created_at, finished_at)
                VALUES (%s, '在庫リスト8月.xlsx', 1, 10, 0, ?, '2026-09-01 10:00:00', '2026-09-01 10:01:00'),
                       (%s, '在庫リスト9月.xlsx', 2, 5, 2, ?, '2026-09-02 11:00:00', '2026-09-02 11:05:00')
                """.formatted(sha(1), sha(2)), bossId, bossId);

        // 告警：1 开启（警告级）+ 1 已读
        jdbcTemplate.update("""
                INSERT INTO sys_alert(type, dedup_key, level, message, status, read_by, read_at)
                VALUES ('DISK_USAGE', 'disk-usage', 2, 'ディスク使用率が閾値を超えました', 0, NULL, NULL),
                       ('BACKUP_STALE', 'backup-stale', 1, 'バックアップが古くなっています', 1, ?, NOW())
                """, bossId);

        // 前端错误：2 条今天 + 1 条 9 天前（出 7 日窗）。旧行先插——
        // 样本取 id 倒序，id 序与时间序一致，recent[0] 才是「最新」
        jdbcTemplate.update("""
                INSERT INTO client_error(message, route, error_id, created_at)
                VALUES ('古いエラー（7日窓の外）', '/old', NULL, DATE_SUB(NOW(), INTERVAL 9 DAY)),
                       ('TypeError: x is not a function', '/inventory', 'E-abc123', NOW()),
                       ('NetworkError on fetch', '/stats', NULL, NOW())
                """);
    }

    private static String sha(int n) {
        return String.format("%064d", n);
    }

    private void insertLog(String action, long itemId) {
        jdbcTemplate.update("""
                INSERT INTO operation_log(action, entity_type, entity_id, operator_id, operator_name)
                VALUES (?, 'item', ?, ?, '管理者')
                """, action, itemId, bossId);
    }

    private MockHttpSession loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .param("username", username).param("password", PASSWORD))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("登录应成功: %s",
                result.getResponse().getContentAsString()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession();
    }

    /** ApiResponse 包裹 → data 节点（UTF-8 取回，日文消息不乱码）。 */
    private JsonNode fetchData(String uri, MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get(uri).session(session))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    @Test
    void adminSeesWhitelistedSystemStatusMatchingSeededVolumes() throws Exception {
        JsonNode data = fetchData("/api/stats/system", loginAs("boss"));

        assertThat(data.path("appVersion").asString()).isEqualTo("dev"); // 测试未打包运行
        assertThat(data.path("flywayVersion").asString()).isNotEmpty();
        assertThat(data.path("startedAt").asString()).isNotEmpty();
        assertThat(data.path("uptimeSeconds").asLong()).isGreaterThanOrEqualTo(0);

        assertThat(data.path("heap").path("usedBytes").asLong()).isPositive();
        assertThat(data.path("heap").path("maxBytes").asLong()).isPositive();
        assertThat(data.path("pool").path("total").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(data.path("sseConnections").asInt()).isZero(); // MockMvc 不开 SSE
        assertThat(data.path("disk").path("path").asString()).isNotEmpty();
        assertThat(data.path("disk").path("usedPercent").asInt()).isGreaterThanOrEqualTo(0);

        assertThat(data.path("volumes").path("items").asLong()).isEqualTo(1);
        assertThat(data.path("volumes").path("ledgers").asLong()).isEqualTo(1);
        assertThat(data.path("volumes").path("operationLogs").asLong()).isEqualTo(4);
        assertThat(data.path("volumes").path("clientErrors").asLong()).isEqualTo(3);

        assertThat(data.path("codeEngine").path("issued").asLong()).isEqualTo(2);
        assertThat(data.path("codeEngine").path("skipped").asLong()).isEqualTo(1);

        JsonNode batches = data.path("excelBatches");
        assertThat(batches.path("total").asLong()).isEqualTo(2);
        assertThat(batches.path("done").asLong()).isEqualTo(1);
        assertThat(batches.path("failed").asLong()).isEqualTo(1);
        assertThat(batches.path("processing").asLong()).isZero();
        assertThat(batches.path("recent").size()).isEqualTo(2);
        assertThat(batches.path("recent").get(0).path("status").asInt()).isEqualTo(2);
        assertThat(batches.path("recent").get(0).path("errorCount").asInt()).isEqualTo(2);
        assertThat(batches.path("recent").get(0).path("originalFilename").asString())
                .isEqualTo("在庫リスト9月.xlsx");

        // 近 7 日：固定 7 格含今天（JST 日界），今天=2，9 天前那条不在窗内
        JsonNode perDay = data.path("clientErrors7d");
        assertThat(perDay.size()).isEqualTo(7);
        assertThat(perDay.get(6).path("date").asString())
                .isEqualTo(LocalDate.now(JST).toString());
        assertThat(perDay.get(6).path("count").asLong()).isEqualTo(2);

        assertThat(data.path("openAlerts").asLong()).isEqualTo(1);

        // backup：本用例时点状态文件为空（createTempFile）或已被 lifecycle 用例删除——
        // 两种形态都=不可知 → null（占位显示，不当故障）
        assertThat(data.path("backup").isNull()).isTrue();
    }

    @Test
    void backupFieldReflectsStatusFileLifecycle() throws Exception {
        MockHttpSession session = loginAs("boss");

        // ① 正常：2 小时前成功（date -Is 格式）→ lastSuccessAt 回显 + staleSeconds ~7200
        String twoHoursAgo = OffsetDateTime.now(JST).minusHours(2).toString();
        writeStatus("{\"lastRunAt\":\"" + twoHoursAgo + "\",\"lastSuccessAt\":\"" + twoHoursAgo
                + "\",\"lastErrorAt\":\"\",\"detail\":\"db 1.2MiB\"}");
        JsonNode backup = fetchData("/api/stats/system", session).path("backup");
        assertThat(backup.path("lastSuccessAt").asString()).isEqualTo(twoHoursAgo);
        assertThat(backup.path("detail").asString()).isEqualTo("db 1.2MiB");
        assertThat(backup.path("staleSeconds").asLong()).isBetween(7140L, 7260L);
        // 展示用 naive JST 墙钟：与 JST 现在差 ~2 小时（dayjs.tz 吞 offset，服务端换算）
        LocalDateTime shownJst = LocalDateTime.parse(backup.path("lastSuccessAtJst").asString());
        assertThat(Duration.between(shownJst, LocalDateTime.now(JST)).toSeconds())
                .isBetween(7140L, 7260L);

        // ② 坏 JSON：诊断页不炸，backup=null
        writeStatus("not json at all {{{");
        assertThat(fetchData("/api/stats/system", session).path("backup").isNull()).isTrue();

        // ③ 文件缺失（从未运行过备份）：backup=null
        Files.delete(BACKUP_STATUS_FILE);
        assertThat(fetchData("/api/stats/system", session).path("backup").isNull()).isTrue();

        // ④ 诊断导出同源携带 backup 段（删除态=null，字段在场）
        MvcResult result = mockMvc.perform(get("/api/diagnostics/export").session(session))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("backup").isNull()).isTrue();
    }

    private void writeStatus(String content) throws IOException {
        Files.writeString(BACKUP_STATUS_FILE, content);
    }

    @Test
    void diagnosticsExportIsAttachmentWithAllSections() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/diagnostics/export").session(loginAs("boss")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("kcgl-diagnostics-")))
                .andReturn();

        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(root.path("generatedAt").asString()).isNotEmpty();
        assertThat(root.path("appVersion").asString()).isEqualTo("dev");
        assertThat(root.path("flywayVersion").asString()).isNotEmpty();
        assertThat(root.path("openAlerts").asLong()).isEqualTo(1);
        assertThat(root.path("volumes").path("operationLogs").asLong()).isEqualTo(4);

        // 告警近 30：含已读行；payload 不导出（字段不出现）
        JsonNode alerts = root.path("alerts");
        assertThat(alerts.size()).isEqualTo(2);
        assertThat(alerts.get(0).path("message").asString())
                .isEqualTo("バックアップが古くなっています"); // id 倒序：后插的已读行在前
        assertThat(alerts.get(0).has("payload")).isFalse();

        // client_error：总量 3 + 近 7 日 + 样本 20 上限（此处 3）
        JsonNode errors = root.path("clientErrors");
        assertThat(errors.path("total").asLong()).isEqualTo(3);
        assertThat(errors.path("last7d").size()).isEqualTo(7);
        assertThat(errors.path("recent").size()).isEqualTo(3);
        assertThat(errors.path("recent").get(0).path("message").asString())
                .isEqualTo("NetworkError on fetch"); // id 倒序：最新在前

        // 环形缓冲：数组字段必在（内容随运行期日志非确定，不作条数断言）
        assertThat(root.path("ringBufferLogs").isArray()).isTrue();
    }

    @Test
    void systemStatusAndExportAreAdminOnly() throws Exception {
        mockMvc.perform(get("/api/stats/system").session(loginAs("eichi")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/diagnostics/export").session(loginAs("eichi")))
                .andExpect(status().isForbidden());
    }
}
