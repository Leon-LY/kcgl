package com.kcgl;

import com.kcgl.common.sse.SseHub;
import com.kcgl.common.sse.SyncEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 生命周期集成测试（M3-③，docs/01 7.6）：真实 Tomcat + 真实 HTTP 连接——
 * 事件流的异步语义无法在 MockMvc 复现，这里是唯一能证明「字节真的流到客户端」的层。
 * 三个生命周期闭合点全量覆盖：登出即时关（会话销毁事件链）、
 * 心跳补杀的两个窗口（账号停用/并发被踢——长连接无后续请求，请求路径过滤器够不着）、
 * 未认证直连 401（前端 EventSource error 分支的探测依据）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@Testcontainers
class SseIntegrationTest {

    /** 读线程的流终止哨兵（服务端 complete 或连接重置后给出）。 */
    static final String EOF = "__EOF__";

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String PASSWORD = "Item-1234-x";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    SseHub sseHub;
    @Autowired
    SessionRegistry sessionRegistry;

    String base;
    long venueId;

    @BeforeEach
    void resetFixtures() {
        base = "http://127.0.0.1:" + port;
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM stock_ledger");
        jdbcTemplate.update("DELETE FROM item_image");
        jdbcTemplate.update("DELETE FROM item");
        jdbcTemplate.update("DELETE FROM seq_item_code");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN ('eichi')");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('eichi', ?, '編集者', 2, 1, 0)
                """, ENCODER.encode(PASSWORD));
        jdbcTemplate.update("DELETE FROM auction_venue");
        jdbcTemplate.update("DELETE FROM price_band");
        jdbcTemplate.update("INSERT INTO price_band(code, lower_bound, upper_bound, enabled) VALUES ('X', 0, 3000, 1)");
        jdbcTemplate.update("INSERT INTO auction_venue(code, name, enabled) VALUES ('HT', '飛騨古民具市', 1)");
        venueId = jdbcTemplate.queryForObject("SELECT id FROM auction_venue WHERE code = 'HT'", Long.class);
    }

    @Test
    void sseLifecycle_helloBroadcast_logoutClosesStream_unauthenticated401() throws Exception {
        Session session = login();
        LinkedBlockingQueue<String> lines = openStream(session);

        // 连接即 HELLO（信封字段齐全）
        String hello = awaitDataLine(lines, "HELLO");
        assertThat(hello).contains("\"seq\"").contains("\"type\":\"HELLO\"");
        assertThat(sseHub.connectionCount()).isGreaterThanOrEqualTo(1);

        // 其他端提交动作 → 全店广播（此处模拟另一会话触发；真实端点接线在 M3-⑤ 落地）
        sseHub.broadcast(SyncEvent.TYPE_ITEM, "HT9-A1X", 7L);
        String event = awaitDataLine(lines, "HT9-A1X");
        assertThat(event).contains("\"type\":\"ITEM\"")
                .contains("\"entity\":\"HT9-A1X\"")
                .contains("\"operatorId\":7");

        // 登出 → HttpSessionDestroyedEvent → 该会话全部连接即时关闭
        HttpRequest logout = HttpRequest.newBuilder(URI.create(base + "/api/auth/logout"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> logoutResponse = session.client().send(logout, HttpResponse.BodyHandlers.ofString());
        assertThat(logoutResponse.statusCode()).as("登出应成功: %s", logoutResponse.body()).isEqualTo(200);
        assertThat(awaitLine(lines, EOF::equals)).isEqualTo(EOF);

        // 未认证直连 → 401（SseEmitter 建立前被认证入口拒绝）
        HttpResponse<String> anonymous = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + "/api/sync/events")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
    }

    @Test
    void heartbeat_closesConnectionWhenAccountDisabled() throws Exception {
        Session session = login();
        LinkedBlockingQueue<String> lines = openStream(session);
        awaitDataLine(lines, "HELLO");

        // 管理员停用账号（等价 UserService 停用后的 DB 状态）——长连接无后续请求，
        // AccountStatusFilter 永无机会执行，唯一防线=心跳周期内补杀（生产 20s，测试直接触发）
        jdbcTemplate.update("UPDATE sys_user SET enabled = 0 WHERE username = 'eichi'");
        sseHub.heartbeat();

        assertThat(awaitLine(lines, EOF::equals)).isEqualTo(EOF);
    }

    @Test
    void heartbeat_closesConnectionWhenSessionExpired() throws Exception {
        Session session = login();
        LinkedBlockingQueue<String> lines = openStream(session);
        awaitDataLine(lines, "HELLO");

        // 第 9 端登录把本会话踢出（expireNow 只标记、无销毁事件）；同时验证关键接线假设：
        // 登录会话确实登记在 SessionRegistry 中——否则被踢会话的 SSE 连接永远无人关闭
        String sessionId = session.jsessionId();
        SessionInformation info = sessionRegistry.getSessionInformation(sessionId);
        assertThat(info).as("登录会话应已登记入 SessionRegistry").isNotNull();
        info.expireNow();
        sseHub.heartbeat();

        assertThat(awaitLine(lines, EOF::equals)).isEqualTo(EOF);
    }

    /**
     * 动作端点全链路（M3-⑤）：HTTP 卖出 → 事务提交 → SseHub 广播 → 字节真的流到
     * 另一会话的打开流——「端点接线了广播」唯一可证明的层（mock 证明不了线上字节）。
     * 到仓广播（entity=null 批次级）先达，卖出广播（entity=管理号）后达，顺序天然分离。
     */
    @Test
    void actionEndpoint_fullChain_broadcastsInventoryEventToOpenStream() throws Exception {
        Session actor = login();
        Session watcher = login();
        LinkedBlockingQueue<String> lines = openStream(watcher);
        awaitDataLine(lines, "HELLO");

        long itemId = createItem(actor);
        postJson(actor, "/api/inventory/arrivals",
                "{\"items\":[{\"itemId\":" + itemId + ",\"clientReqId\":\"sse-arr\"}]}");
        assertThat(awaitDataLine(lines, "INVENTORY"))
                .as("到仓批次级广播应先达（entity=null）").contains("\"type\":\"INVENTORY\"");

        String itemCode = jdbcTemplate.queryForObject(
                "SELECT item_code FROM item WHERE id = ?", String.class, itemId);
        postJson(actor, "/api/inventory/sell",
                "{\"itemId\":" + itemId + ",\"clientReqId\":\"sse-sell\",\"soldPrice\":15000}");
        String event = awaitDataLine(lines, itemCode);
        assertThat(event).contains("\"type\":\"INVENTORY\"")
                .contains("\"entity\":\"" + itemCode + "\"");
    }

    /**
     * 商品/图片域广播（M3-③ 补全）：录入/作废=ITEM、照片绑定=IMAGE——他端在途
     * 清单/本日会话的失效重取依赖这些事件（前端 sync.spec E2E 的后端锚点）。
     * 经真实端点证明「提交后字节真的流到另一会话」；同 clientReqId 重放作废
     * 读回原结果 200 但不再广播（读回早退先于广播）。
     */
    @Test
    void itemLifecycle_fullChain_broadcastsItemAndImageEventsToOpenStream() throws Exception {
        Session actor = login();
        Session watcher = login();
        LinkedBlockingQueue<String> lines = openStream(watcher);
        awaitDataLine(lines, "HELLO");

        String body = postJson(actor, "/api/items",
                "{\"clientReqId\":\"sse-item-create\",\"venueId\":" + venueId
                        + ",\"buyDate\":\"2026-09-15\",\"purchasePrice\":1000,\"warehouse\":1}");
        long itemId = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
        String itemCode = body.replaceAll(".*\"itemCode\":\"([^\"]+)\".*", "$1");

        assertThat(awaitDataLine(lines, itemCode))
                .as("录入提交后应广播 ITEM 事件（entity=管理号）")
                .contains("\"type\":\"ITEM\"")
                .contains("\"entity\":\"" + itemCode + "\"");

        // 照片补传（multipart）→ IMAGE 事件（entity=itemId）：他端缩略图刷新的依据
        postMultipartImage(actor, itemId, "5f0f2b1a-1111-4222-8333-444455556666");
        assertThat(awaitDataLine(lines, "IMAGE"))
                .as("照片上传提交后应广播 IMAGE 事件（entity=itemId）")
                .contains("\"type\":\"IMAGE\"")
                .contains("\"entity\":\"" + itemId + "\"");

        postJson(actor, "/api/items/" + itemId + "/void",
                "{\"clientReqId\":\"sse-item-void\",\"reason\":\"SSEITEM\"}");
        assertThat(awaitDataLine(lines, itemCode))
                .as("作废提交后应广播 ITEM 事件")
                .contains("\"type\":\"ITEM\"")
                .contains("\"entity\":\"" + itemCode + "\"");

        // 重放（同 clientReqId 二发作废）读回原结果 200，但不再广播——2s 内不应
        // 出现新 data 行（空行是 SSE 事件分隔符，跳过不算）
        postJson(actor, "/api/items/" + itemId + "/void",
                "{\"clientReqId\":\"sse-item-void\",\"reason\":\"SSEITEM\"}");
        String stale = lines.poll(2, TimeUnit.SECONDS);
        while (stale != null && !stale.startsWith("data:")) {
            stale = lines.poll(2, TimeUnit.SECONDS);
        }
        assertThat(stale).as("重放不应产生新广播 data 行，实际收到: %s", stale).isNull();
    }

    // ------------------------------------------------------------- 夹具与助手

    /** 登录后的客户端与其 cookie 存储（会话 id 就藏在 JSESSIONID 里）。 */
    private record Session(HttpClient client, CookieManager cookies) {
        String jsessionId() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(c -> "JSESSIONID".equals(c.getName()))
                    .map(HttpCookie::getValue)
                    .findFirst().orElseThrow();
        }
    }

    private Session login() throws Exception {
        CookieManager cookies = new CookieManager();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .cookieHandler(cookies)
                .build();
        HttpRequest login = HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=eichi&password=" + PASSWORD))
                .build();
        HttpResponse<String> response = client.send(login, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("登录应成功: %s", response.body()).isEqualTo(200);
        return new Session(client, cookies);
    }

    /** 建立事件流（响应头断言）并启动读线程把行灌进队列，流终止时补 EOF 哨兵。 */
    private LinkedBlockingQueue<String> openStream(Session session) throws Exception {
        HttpRequest events = HttpRequest.newBuilder(URI.create(base + "/api/sync/events"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<InputStream> stream = session.client().send(events,
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(stream.statusCode()).as("事件流应建立成功").isEqualTo(200);
        assertThat(stream.headers().firstValue("X-Accel-Buffering")).contains("no");
        assertThat(stream.headers().firstValue("Content-Type").orElse(""))
                .contains("text/event-stream");

        LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread.startVirtualThread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException ignored) {
                // 服务端断开连接属预期终态（complete 或连接重置）
            }
            lines.add(EOF);
        });
        return lines;
    }

    /** 轮询等待首条满足谓词的行（其余行留档用于失败信息）；10s 超时失败。 */
    private String awaitLine(LinkedBlockingQueue<String> lines, Predicate<String> match)
            throws InterruptedException {
        List<String> seen = new ArrayList<>();
        String found = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (found == null && System.nanoTime() < deadline) {
            String line = lines.poll(500, TimeUnit.MILLISECONDS);
            if (line == null) {
                continue;
            }
            if (match.test(line)) {
                found = line;
            } else {
                seen.add(line);
            }
        }
        assertThat(found).as("10s 内未等到目标行；此前已收到 %d 行: %s", seen.size(), seen).isNotNull();
        return found;
    }

    private String awaitDataLine(LinkedBlockingQueue<String> lines, String marker)
            throws InterruptedException {
        return awaitLine(lines, line -> line.startsWith("data:") && line.contains(marker));
    }

    /** POST JSON 并断言 200（错误体进失败信息）。 */
    private String postJson(Session session, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = session.client().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("POST %s 应成功: %s", path, response.body()).isEqualTo(200);
        return response.body();
    }

    /** 最小 JPEG 夹具（图片上传 magic/解码校验的前提）。 */
    private static byte[] jpeg(int width, int height) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** multipart 图片上传（原始 HttpClient 无 multipart 助手，手工拼边界体）。 */
    private String postMultipartImage(Session session, long itemId, String clientUuid) throws Exception {
        String boundary = "kcgl-sse-" + System.nanoTime();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"clientUuid\"\r\n\r\n"
                + clientUuid + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"itemId\"\r\n\r\n"
                + itemId + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(jpeg(64, 48));
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/images"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .build();
        HttpResponse<String> response = session.client().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("图片上传应成功: %s", response.body()).isEqualTo(200);
        return response.body();
    }

    /** 录一件在途并返回 id（动作全链路的起点）。 */
    private long createItem(Session session) throws Exception {
        String body = postJson(session, "/api/items",
                "{\"clientReqId\":\"sse-item\",\"venueId\":" + venueId
                        + ",\"buyDate\":\"2026-09-15\",\"purchasePrice\":1000,\"warehouse\":1,"
                        + "\"remark\":\"SSE全鏈路テスト\"}");
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }
}
