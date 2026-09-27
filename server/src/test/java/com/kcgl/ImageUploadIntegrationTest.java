package com.kcgl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 图片管线集成测试（M2-5，docs/01 7.5）：magic 白名单 / 5MB 上限 / 读头像素上限 /
 * EXIF 剥离 / clientUuid 幂等 200 读回 / 9 图上限 / 角色矩阵 / 匿名直出。
 * 图片落盘到 @TempDir（与库表同断言，防「库有盘无」的半成品态）。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class ImageUploadIntegrationTest {

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
    static void imageDirProperty(DynamicPropertyRegistry registry) {
        registry.add("kcgl.image.dir", () -> imageDir.toString());
    }

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String PASSWORD = "Img-1234-x";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;

    long itemId;

    @BeforeEach
    void resetFixtures() {
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM item_image");
        jdbcTemplate.update("DELETE FROM item");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN ('ga1','eichi','miru')");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('ga1', ?, '管理者', 1, 1, 0), ('eichi', ?, '編集者', 2, 1, 0), ('miru', ?, '閲覧者', 3, 1, 0)
                """, ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD));
        jdbcTemplate.update("""
                INSERT INTO item(item_code, venue_id, venue_code, `year`, year_code, buy_month,
                    seq_prefix, seq_no, buy_date, purchase_price, price_band_code, warehouse, created_by)
                VALUES ('HTK9-A1X', 1, 'HT', 2026, 'K', 9, 'A', 1, '2026-09-15', 1000, 'X', 1, 1)
                """);
        itemId = jdbcTemplate.queryForObject("SELECT id FROM item WHERE item_code = 'HTK9-A1X'", Long.class);
    }

    private MockHttpSession loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .param("username", username).param("password", PASSWORD))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("登录应成功: %s",
                result.getResponse().getContentAsString()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession();
    }

    // ------------------------------------------------------------------ 图片夹具

    /** JPEG（640×480——真实拍摄尺寸量级，256px 缩略必然为缩小；非均匀色块避免极端压缩）。 */
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

    /** 有效 SOI 后插入 APP1/Exif 段（含伪 GPS 坐标文本）——断言重编码后必然消失。 */
    private static byte[] jpegWithExif() {
        byte[] jpeg = jpeg(64, 48);
        byte[] payload = new byte[16];
        byte[] header = "Exif\0\0II*\0\0\0\0\0".getBytes(StandardCharsets.US_ASCII);
        byte[] gps = "35.6812,139.7671".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, payload, 0, header.length);
        int segLen = payload.length + gps.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{jpeg[0], jpeg[1]});          // SOI
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xE1});  // APP1
        out.writeBytes(new byte[]{(byte) (segLen >> 8), (byte) segLen});
        out.writeBytes(payload);
        out.writeBytes(gps);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private MockMultipartFile part(byte[] data) {
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", data);
    }

    // ------------------------------------------------------------------ 用例

    @Test
    void uploadJpeg_byEditor_persistsAndReturnsUrls() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        String clientUuid = UUID.randomUUID().toString();

        mockMvc.perform(multipart("/api/images").file(part(jpeg(640, 480)))
                        .param("clientUuid", clientUuid).param("itemId", String.valueOf(itemId))
                        .session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.clientUuid").value(clientUuid))
                .andExpect(jsonPath("$.data.url").value("/img/orig/" + pathOf(clientUuid)))
                .andExpect(jsonPath("$.data.thumbUrl").value("/img/thumb/" + pathOf(clientUuid)))
                .andExpect(jsonPath("$.data.sortOrder").value(0));

        String storedPath = jdbcTemplate.queryForObject(
                "SELECT stored_path FROM item_image WHERE client_uuid = ?", String.class, clientUuid);
        assertThat(storedPath).isEqualTo(pathOf(clientUuid));
        // 库有盘有（原图+缩略图），且重编码为 JPEG（FF D8 起）
        byte[] orig = Files.readAllBytes(imageDir.resolve("orig").resolve(storedPath));
        byte[] thumb = Files.readAllBytes(imageDir.resolve("thumb").resolve(storedPath));
        assertThat(orig[0]).isEqualTo((byte) 0xFF);
        assertThat(orig[1]).isEqualTo((byte) 0xD8);
        assertThat(orig.length).isGreaterThan(100);
        assertThat(thumb.length).isGreaterThan(100).isLessThan(orig.length);
    }

    @Test
    void upload_sameClientUuid_replaysOriginalRowNoDuplicate() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        String clientUuid = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(multipart("/api/images").file(part(jpeg(64, 48)))
                        .param("clientUuid", clientUuid).param("itemId", String.valueOf(itemId))
                        .session(editor))
                .andExpect(status().isOk()).andReturn();
        long firstId = readId(first);

        // 网络切换响应丢失后的机器重放（新文件实例、同幂等键）
        MvcResult replay = mockMvc.perform(multipart("/api/images").file(part(jpeg(64, 48)))
                        .param("clientUuid", clientUuid).param("itemId", String.valueOf(itemId))
                        .session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(firstId))
                .andReturn();

        assertThat(readId(replay)).isEqualTo(firstId);
        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM item_image WHERE client_uuid = ?", Long.class, clientUuid);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void uploadJpeg_exifGps_strippedByReencode() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        String clientUuid = UUID.randomUUID().toString();
        byte[] withExif = jpegWithExif();
        assertThat(new String(withExif, StandardCharsets.US_ASCII)).contains("Exif");
        assertThat(new String(withExif, StandardCharsets.US_ASCII)).contains("35.6812");

        mockMvc.perform(multipart("/api/images").file(part(withExif))
                        .param("clientUuid", clientUuid).param("itemId", String.valueOf(itemId))
                        .session(editor))
                .andExpect(status().isOk());

        String storedPath = jdbcTemplate.queryForObject(
                "SELECT stored_path FROM item_image WHERE client_uuid = ?", String.class, clientUuid);
        String stored = new String(Files.readAllBytes(imageDir.resolve("orig").resolve(storedPath)),
                StandardCharsets.ISO_8859_1);
        assertThat(stored).doesNotContain("Exif");
        assertThat(stored).doesNotContain("35.6812");
    }

    @Test
    void uploadPng_withAlpha_flattenedToWhiteAccepted() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        String clientUuid = UUID.randomUUID().toString();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BufferedImage png = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(png, "png", out);

        mockMvc.perform(multipart("/api/images").file(
                        new MockMultipartFile("file", "photo.png", "image/png", out.toByteArray()))
                        .param("clientUuid", clientUuid).param("itemId", String.valueOf(itemId))
                        .session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageType").value(1));
    }

    @Test
    void upload_nonJpegOrPngFile_400005() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        mockMvc.perform(multipart("/api/images").file(
                        new MockMultipartFile("file", "note.txt", "text/plain",
                                "これはテキストです".getBytes(StandardCharsets.UTF_8)))
                        .param("clientUuid", UUID.randomUUID().toString())
                        .param("itemId", String.valueOf(itemId))
                        .session(editor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400005));
    }

    @Test
    void upload_over5mb_400006() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        byte[] big = new byte[5_242_881]; // 5MB + 1
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;

        mockMvc.perform(multipart("/api/images").file(part(big))
                        .param("clientUuid", UUID.randomUUID().toString())
                        .param("itemId", String.valueOf(itemId))
                        .session(editor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400006));
    }

    @Test
    void upload_pixelBomb9000px_rejected400007BeforeDecode() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        // 真实 JPEG（9000×60，均匀底色体积很小）——读头发现宽 9000 > 8000 即拒
        mockMvc.perform(multipart("/api/images").file(part(jpeg(9000, 60)))
                        .param("clientUuid", UUID.randomUUID().toString())
                        .param("itemId", String.valueOf(itemId))
                        .session(editor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400007));
    }

    @Test
    void upload_itemNotFound_404001() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        mockMvc.perform(multipart("/api/images").file(part(jpeg(32, 32)))
                        .param("clientUuid", UUID.randomUUID().toString())
                        .param("itemId", "999999")
                        .session(editor))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404001));
    }

    @Test
    void upload_tenthImage_400008_maxNinePerItem() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        for (int i = 1; i <= 9; i++) {
            mockMvc.perform(multipart("/api/images").file(part(jpeg(32, 32)))
                            .param("clientUuid", UUID.randomUUID().toString())
                            .param("itemId", String.valueOf(itemId))
                            .session(editor))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sortOrder").value(i - 1));
        }
        mockMvc.perform(multipart("/api/images").file(part(jpeg(32, 32)))
                        .param("clientUuid", UUID.randomUUID().toString())
                        .param("itemId", String.valueOf(itemId))
                        .session(editor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400008));
    }

    @Test
    void upload_byViewer_403_canListImages() throws Exception {
        MockHttpSession viewer = loginAs("miru");
        mockMvc.perform(multipart("/api/images").file(part(jpeg(32, 32)))
                        .param("clientUuid", UUID.randomUUID().toString())
                        .param("itemId", String.valueOf(itemId))
                        .session(viewer))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/items/{id}/images", itemId).session(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void getOrigImage_anonymous_200_loginNotRequired() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        String clientUuid = UUID.randomUUID().toString();
        mockMvc.perform(multipart("/api/images").file(part(jpeg(32, 32)))
                        .param("clientUuid", clientUuid).param("itemId", String.valueOf(itemId))
                        .session(editor))
                .andExpect(status().isOk());

        mockMvc.perform(get("/img/orig/" + pathOf(clientUuid)))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray().length)
                        .isGreaterThan(100));
    }

    // ------------------------------------------------------------------ 工具

    /** 期望落盘路径（yyyy/MM 取上传时刻 JST——测试跨月边界由 CI 时区钉死项覆盖）。 */
    private static String pathOf(String clientUuid) {
        return java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tokyo"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM"))
                + "/" + clientUuid + ".jpg";
    }

    private static long readId(MvcResult result) throws IOException {
        String body = result.getResponse().getContentAsString();
        int idx = body.indexOf("\"id\":");
        assertThat(idx).as("响应应含 id: %s", body).isGreaterThan(0);
        int start = idx + 5;
        int end = body.indexOf(',', start);
        return Long.parseLong(body.substring(start, end));
    }
}
