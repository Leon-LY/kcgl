package com.kcgl;

import com.kcgl.common.obs.SelfCheckService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 每日自检集成测试（M3-⑦，docs/01 5.3/9.3）：五节检查（账实/计数器/数据量/磁盘/
 * 图片双向对账）+ 按节落 sys_alert（同 dedup 键 upsert 去重）+ 手动「帳実自検」
 * 端点角色矩阵。阈值经 DynamicPropertySource 压低（item=1）使数据量告警可确定性
 * 触发；磁盘水位 101% 恒通过（CI runner 真实水位不可控）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SelfCheckIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    @TempDir
    static Path imageDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("kcgl.security.allowed-origins", () -> "https://kcgl.example.com");
        registry.add("kcgl.image.dir", () -> imageDir.toString());
        registry.add("kcgl.self-check.scheduled", () -> "false");
        registry.add("kcgl.self-check.item-volume-threshold", () -> "1");
        registry.add("kcgl.self-check.disk-warn-percent", () -> "101");
    }

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String PASSWORD = "Chk-1234-x";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    SelfCheckService selfCheck;

    long venueId;

    @BeforeEach
    void resetFixtures() throws IOException {
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM stock_ledger");
        jdbcTemplate.update("DELETE FROM item");
        jdbcTemplate.update("DELETE FROM seq_item_code");
        jdbcTemplate.update("DELETE FROM item_image");
        jdbcTemplate.update("DELETE FROM sys_alert");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN ('boss','eichi','miru')");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('boss', ?, '管理者', 1, 1, 0), ('eichi', ?, '編集者', 2, 1, 0), ('miru', ?, '閲覧者', 3, 1, 0)
                """, ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD));
        jdbcTemplate.update("DELETE FROM auction_venue");
        jdbcTemplate.update("DELETE FROM price_band");
        jdbcTemplate.update("DELETE FROM year_code");
        jdbcTemplate.update("INSERT INTO year_code(`year`, code) VALUES (2026,'K')");
        jdbcTemplate.update("INSERT INTO price_band(code, lower_bound, upper_bound, enabled) VALUES ('X', 0, 3000, 1)");
        jdbcTemplate.update("INSERT INTO auction_venue(code, name, enabled) VALUES ('HT', '飛騨古民具市', 1)");
        venueId = jdbcTemplate.queryForObject("SELECT id FROM auction_venue WHERE code = 'HT'", Long.class);
        cleanImageDir();
    }

    // ------------------------------------------------------------- 夹具与助手

    private void cleanImageDir() throws IOException {
        for (String sub : new String[]{"orig", "thumb"}) {
            Path root = imageDir.resolve(sub);
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
    }

    private MockHttpSession loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .param("username", username).param("password", PASSWORD))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("登录应成功: %s",
                result.getResponse().getContentAsString()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession();
    }

    /** 录一件在途（名古屋仓）并返回 [id, itemCode]。 */
    private String[] createItem(MockHttpSession session, String clientReqId) throws Exception {
        String json = mockMvc.perform(post("/api/items").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"" + clientReqId + "\",\"venueId\":" + venueId
                                + ",\"buyDate\":\"2026-09-15\",\"purchasePrice\":1000,\"warehouse\":1,"
                                + "\"remark\":\"自検テスト\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new String[]{
                json.replaceAll(".*\"id\":(\\d+).*", "$1"),
                json.replaceAll(".*\"itemCode\":\"([^\"]+)\".*", "$1")};
    }

    private void arrive(MockHttpSession session, long itemId, String clientReqId) throws Exception {
        mockMvc.perform(post("/api/inventory/arrivals").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"itemId\":" + itemId + ",\"clientReqId\":\"" + clientReqId + "\"}]}"))
                .andExpect(status().isOk());
    }

    private void uploadImage(MockHttpSession session, long itemId) throws Exception {
        mockMvc.perform(multipart("/api/images").file(part(jpeg(64, 48)))
                        .param("clientUuid", UUID.randomUUID().toString())
                        .param("itemId", String.valueOf(itemId))
                        .session(session))
                .andExpect(status().isOk());
    }

    /** JPEG 640×480 量级色块（同 ImageUploadIntegrationTest 夹具形态）。 */
    private static byte[] jpeg(int width, int height) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setPaint(Color.WHITE);
                g.fillRect(0, 0, width, height);
                g.setPaint(new Color(0x20, 0x62, 0xA6));
                g.fillOval(8, 8, width - 16, height - 16);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MockMultipartFile part(byte[] data) {
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", data);
    }

    // ------------------------------------------------------------- 用例

    @Test
    void check_cleanStateAfterNormalFlow_allSectionsOk_noAlerts_adminOnlyEndpoint()
            throws Exception {
        MockHttpSession editor = loginAs("eichi");
        MockHttpSession boss = loginAs("boss");
        MockHttpSession viewer = loginAs("miru");
        String[] item = createItem(editor, "sc-a");
        arrive(editor, Long.parseLong(item[0]), "sc-a-arr");
        uploadImage(editor, Long.parseLong(item[0]));

        SelfCheckService.SelfCheckReport report = selfCheck.check();
        assertThat(report.ok()).as("正常流后全节通过（磁盘阈值 101% 恒过）").isTrue();
        assertThat(report.ledger().ok()).isTrue();
        assertThat(report.ledger().balances()).isNotEmpty();
        assertThat(report.counters().ok()).isTrue();
        assertThat(report.volumes().ok()).as("1 件未超阈值 1").isTrue();
        assertThat(report.volumes().itemCount()).isEqualTo(1);
        assertThat(report.imageAudit().ok()).isTrue();
        assertThat(report.imageAudit().orphanCount()).isZero();
        assertThat(report.imageAudit().missingCount()).isZero();

        // 手动「帳実自検」：管理员 200+报告；E/V 403；干净状态不落告警
        mockMvc.perform(post("/api/self-check").session(boss))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.ledger.ok").value(true))
                .andExpect(jsonPath("$.data.imageAudit.orphanCount").value(0));
        mockMvc.perform(post("/api/self-check").session(editor))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/self-check").session(viewer))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_alert", Long.class)).isZero();
    }

    @Test
    void check_detectsAllAnomalies_recordsSectionAlerts_withDedup() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        String[] a = createItem(editor, "an-a");
        String[] b = createItem(editor, "an-b");
        arrive(editor, Long.parseLong(a[0]), "an-a-arr");
        arrive(editor, Long.parseLong(b[0]), "an-b-arr");

        // 破坏四节：假卖出无流水（账实漂移）/ 计数器倒退 / 2 件>阈值 1（数据量）/
        // 表引用幽灵文件（缺失）+ 盘上无表文件（孤儿）
        jdbcTemplate.update("UPDATE item SET stock_status = 2 WHERE id = ?", Long.parseLong(a[0]));
        jdbcTemplate.update("UPDATE seq_item_code SET cur_seq = 0");
        long editorId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'eichi'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO item_image(item_id, client_uuid, stored_path, thumb_path,
                        image_type, sort_order, created_by, created_at)
                VALUES (?, ?, '2026/09/ghost.jpg', '2026/09/ghost.jpg', 1, 0, ?, NOW(3))
                """, Long.parseLong(b[0]), UUID.randomUUID().toString(), editorId);
        Path orphanDir = imageDir.resolve(Paths.get("orig", "2026", "09"));
        Files.createDirectories(orphanDir);
        Files.writeString(orphanDir.resolve("orphan.jpg"), "x");

        SelfCheckService.SelfCheckReport report = selfCheck.checkAndAlert();
        assertThat(report.ok()).isFalse();
        assertThat(report.ledger().ok()).isFalse();
        assertThat(report.ledger().driftCount()).isEqualTo(1);
        assertThat(report.ledger().drifts().get(0).itemId()).isEqualTo(Long.parseLong(a[0]));
        assertThat(report.counters().ok()).isFalse();
        assertThat(report.counters().mismatches()).hasSize(1);
        assertThat(report.counters().mismatches().get(0).curSeq()).isZero();
        assertThat(report.counters().mismatches().get(0).maxSeq()).isEqualTo(2);
        assertThat(report.volumes().ok()).isFalse();
        assertThat(report.volumes().itemCount()).isEqualTo(2);
        assertThat(report.imageAudit().ok()).isFalse();
        assertThat(report.imageAudit().missingCount()).as("stored+thumb 两条幽灵引用").isEqualTo(2);
        assertThat(report.imageAudit().orphanCount()).isEqualTo(1);

        // 按节落告警：四类五行（IMAGE_AUDIT 双键）；磁盘 101% 不触发
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_alert", Long.class)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_alert WHERE type = 'RECONCILE_MISMATCH' AND level = 3",
                Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_alert WHERE type = 'SEQ_COUNTER_MISMATCH' AND level = 3",
                Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_alert WHERE type = 'DATA_VOLUME' AND level = 2",
                Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_alert WHERE type = 'IMAGE_AUDIT'", Long.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_alert WHERE type = 'DISK_USAGE'", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, payload FROM sys_alert WHERE type = 'RECONCILE_MISMATCH'"))
                .containsEntry("status", 0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payload FROM sys_alert WHERE type = 'RECONCILE_MISMATCH'", String.class))
                .contains("drifts");

        // 同键 upsert：再跑一轮告警不刷屏
        selfCheck.checkAndAlert();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_alert", Long.class)).isEqualTo(5);

        // 告警列表（管理员红点数据源）
        MockHttpSession boss = loginAs("boss");
        mockMvc.perform(get("/api/alerts").session(boss))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.list[0].type").exists());
    }

    // ------------------------------------------------------------- 计数器跨前缀回归（D-058 H）

    /**
     * 前缀进位后（A 前缀遗留号高于 B 前缀现值）健康态不得误报：
     * A1..A9+B1..B2、计数器 (B,2)——跨前缀 MAX(seq_no)=9＞cur_seq 2 旧 SQL 必误报
     * 「カウンタ不整合」（单桶过 99 件即永久 ERROR，狼来了效应），按当前前缀比较才是健康语义。
     */
    @Test
    void checkCounters_afterPrefixCarryWithLegacyHigherSeq_notFlagged() {
        long editorId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'eichi'", Long.class);
        for (int seq = 1; seq <= 9; seq++) {
            seedCounterItem(editorId, "HTK9-A" + seq + "X", "A", seq);
        }
        for (int seq = 1; seq <= 2; seq++) {
            seedCounterItem(editorId, "HTK9-B" + seq + "X", "B", seq);
        }
        jdbcTemplate.update("""
                INSERT INTO seq_item_code(venue_id, year, month, cur_prefix, cur_seq)
                VALUES (?, 2026, 9, 'B', 2)
                """, venueId);

        SelfCheckService.SelfCheckReport report = selfCheck.check();
        assertThat(report.counters().ok()).as("前缀进位后按当前前缀比较应健康").isTrue();
        assertThat(report.counters().mismatches()).isEmpty();
    }

    /** 当前前缀内计数器真实落后（生成必撞 uk 前兆）仍须告警，且明细携带前缀。 */
    @Test
    void checkCounters_currentPrefixBehindMaxSeq_flaggedWithPrefixDetail() {
        long editorId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'eichi'", Long.class);
        seedCounterItem(editorId, "HTK9-A1X", "A", 1);
        seedCounterItem(editorId, "HTK9-B1X", "B", 1);
        seedCounterItem(editorId, "HTK9-B2X", "B", 2);
        jdbcTemplate.update("""
                INSERT INTO seq_item_code(venue_id, year, month, cur_prefix, cur_seq)
                VALUES (?, 2026, 9, 'B', 0)
                """, venueId);

        SelfCheckService.SelfCheckReport report = selfCheck.check();
        assertThat(report.counters().ok()).isFalse();
        assertThat(report.counters().mismatches()).hasSize(1);
        SelfCheckService.CounterMismatch mismatch = report.counters().mismatches().get(0);
        assertThat(mismatch.curPrefix()).isEqualTo("B");
        assertThat(mismatch.curSeq()).isZero();
        assertThat(mismatch.maxSeq()).as("仅统计 B 前缀的行").isEqualTo(2);
    }

    /** 直插 item 行（在途态，不写 ledger）供计数器检查夹具用。 */
    private void seedCounterItem(long userId, String itemCode, String seqPrefix, int seqNo) {
        jdbcTemplate.update("""
                INSERT INTO item(item_code, venue_id, venue_code, year, year_code, buy_month,
                    seq_prefix, seq_no, buy_date, purchase_price, price_band_code, warehouse,
                    stock_status, sale_status, created_by)
                VALUES (?, ?, 'HT', 2026, 'K', 9, ?, ?, '2026-09-15', 1000, 'X', 1, 0, 0, ?)
                """, itemCode, venueId, seqPrefix, seqNo, userId);
    }
}
