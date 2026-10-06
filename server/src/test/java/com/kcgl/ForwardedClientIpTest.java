package com.kcgl;

import com.kcgl.module.auth.LoginLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户端真实 IP 解析（B1 前提）——**必须走真实 HTTP**：MockMvc 不经过 Tomcat 的
 * RemoteIpValve，覆盖不到本缺陷（这正是 B1 首版修复漏判之处）。
 *
 * <p>生产拓扑：系统唯一入口是 nginx（app 服务不映射端口），nginx 以
 * {@code X-Forwarded-For: $proxy_add_x_forwarded_for} 转发。app 若不解析转发头，
 * {@code request.getRemoteAddr()} 对所有外部客户端都是**同一个 nginx 容器 IP**，
 * 于是登录节流的 (账号+IP) 维度整体退化为账号维度：任一客户端连错 5 次即锁死该账号的
 * 全部来源（跨用户 DoS），且「≥2 个源 IP 才升级账号级锁」永不触发（账号级锁形同废置）。
 *
 * <p>本用例以 127.0.0.1 为直连对端（属 Tomcat 默认可信代理段，模拟 nginx），用 XFF 区分客户端；
 * XFF 取公网地址段，避免被误判为可信代理而不被采信。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@Testcontainers
class ForwardedClientIpTest {

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

    /** 公网地址段（TEST-NET-3）：不会被当作可信内部代理，才能作为"客户端 IP"参与判定。 */
    static final String CLIENT_A = "203.0.113.1";
    static final String CLIENT_B = "203.0.113.2";
    static final String CLIENT_C = "203.0.113.3";

    /** 与 LoginLockService.MAX_ATTEMPTS 同值：用例独立声明契约阈值，阈值变了要在这里也看见。 */
    static final int MAX_ATTEMPTS = 5;

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    LoginLockService loginLockService;

    private final RestTemplate restTemplate = new RestTemplate();

    ForwardedClientIpTest() {
        // 断言对象就是 401/423 这类状态码本身，不能让 4xx 抛异常
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            protected boolean hasError(HttpStatusCode statusCode) {
                return false;
            }
        });
    }

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM sys_user");
        loginLockService.reset();
    }

    private void insertUser(String username) {
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES (?, ?, ?, 2, 1, 0)
                """, username, ENCODER.encode(RAW_PWD), username);
    }

    /** 以指定 X-Forwarded-For 身份发一次登录（模拟 nginx 转发来的外部客户端）。 */
    private ResponseEntity<String> login(String forwardedFor, String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("X-Forwarded-For", forwardedFor);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", username);
        form.add("password", password);
        return restTemplate.exchange("http://127.0.0.1:" + port + "/api/auth/login",
                HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
    }

    private int status(String forwardedFor, String username, String password) {
        return login(forwardedFor, username, password).getStatusCode().value();
    }

    /**
     * B1 回归（核心）：不同源 IP 必须是不同的节流维度。
     * 未解析转发头时两个"客户端"在 app 眼里同为 127.0.0.1，末段断言必然拿到 423 → RED。
     */
    @Test
    @Tag("regression")
    void forwardedFor_distinguishesClients_singleIpFailuresDoNotLockAccount() {
        insertUser("fwd");

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertThat(status(CLIENT_A, "fwd", "wrong-password"))
                    .as("第 %d 次失败应为 401", i + 1)
                    .isEqualTo(401);
        }

        assertThat(status(CLIENT_A, "fwd", RAW_PWD))
                .as("同一源 IP 已被防爆破拦住")
                .isEqualTo(423);
        assertThat(status(CLIENT_B, "fwd", RAW_PWD))
                .as("账号不得被单一源 IP 锁死——其他来源的正确密码必须放行")
                .isEqualTo(200);
    }

    /**
     * 伪造面取向（安全属性，非等价改写）：nginx 用 {@code $proxy_add_x_forwarded_for} 会把客户端
     * 自带的 XFF 拼在真实地址**之前**——链形如「客户端伪造值, 真实来源」。若解析取链左端，
     * 攻击者每次换一个假 IP 就换一个节流桶，防爆破被整体绕过；取右端（真实来源）才安全。
     *
     * <p>本用例即该取向的判据：以「伪造值, CLIENT_A」连错 5 次后，CLIENT_A 必须已被锁（右端被采信），
     * 而伪造值本身不得成为独立桶（否则左端被采信，第二个断言会拿到 423 而非 200）。
     */
    @Test
    @Tag("regression")
    void forwardedFor_spoofedLeftEntryIgnored_realClientDecides() {
        insertUser("spoof");
        String spoofedChain = "198.51.100.9, " + CLIENT_A;

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            login(spoofedChain, "spoof", "wrong-password");
        }

        assertThat(status(CLIENT_A, "spoof", RAW_PWD))
                .as("真实来源（链右端）应已命中锁定")
                .isEqualTo(423);
        assertThat(status("198.51.100.9", "spoof", RAW_PWD))
                .as("伪造的左端值不得自成一个节流桶")
                .isEqualTo(200);
    }

    /**
     * 账号级持久锁的升级路径：失败来自 ≥2 个不同源 IP 才判定为分布式撞库并写 sys_user.locked_until。
     * 未解析转发头时不同客户端共用同一 IP，升级永不触发（账号级锁废置）→ RED。
     */
    @Test
    @Tag("regression")
    void forwardedFor_multipleClients_escalateToAccountWideLock() {
        insertUser("fwdesc");

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            login(CLIENT_A, "fwdesc", "wrong-password");
        }
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            login(CLIENT_B, "fwdesc", "wrong-password");
        }

        ResponseEntity<String> third = login(CLIENT_C, "fwdesc", RAW_PWD);

        assertThat(third.getStatusCode().value()).as("第三方来源应被账号级锁拦下").isEqualTo(423);
        assertThat(third.getBody()).contains("423001");
    }
}
