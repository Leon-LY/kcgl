package com.kcgl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 未认证请求不得创建 HTTP 会话（D-081 回归锚点）。
 *
 * <p>Spring Security 默认启用 {@link HttpSessionRequestCache}：401 路径上
 * {@code ExceptionTranslationFilter.sendStartAuthentication()} 会调
 * {@code requestCache.saveRequest()}，而它内部是 {@code request.getSession(true)}。
 * 该缓存的唯一用途是「401 时存下原请求 → 登录后 302 跳回原页」；本应用是 JSON SPA +
 * 自定义 AuthenticationEntryPoint（{@code LoginJsonHandlers}），永远返回 401 JSON、
 * 从不重定向，这条链一次也用不上。代价却极重：**每个未认证请求凭空建一个 HTTP 会话**且
 * 客户端不接 Cookie 时永不复用（约 3.7KB/次）。
 *
 * <p>本地预演实测（修复前）：2 万次未认证请求 → Tomcat 活跃会话恰好 +20000（1:1）；
 * 10 万次未认证请求把实例打到 44 次 {@code OutOfMemoryError}。任何不带 Cookie 的
 * 客户端（爬虫/拨测/剥 Cookie 的代理）都能据此打满内存。修复 =
 * SecurityConfig {@code .requestCache(cache -> cache.disable())}。
 *
 * <p>本测试锁死该修复：一旦有人为「登录后跳回」顺手把 RequestCache 打开，
 * 这里立刻红。下面的 {@code requestCacheSaveRequest_} 用例成对存在——它证明
 * 「建的会话就是 RequestCache 建的」（机制侧的绿灯），而业务链路的用例要求
 * 同一断言形状**恰好相反**（业务侧必须无会话）。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class SessionLeakIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String RAW_PWD = "Slk-1234-x";

    /** 未认证请求的采样次数：与 D-081 的复现实验同量级（原实验为 2 万，此处抽 50）。 */
    private static final int SAMPLES = 50;

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetUsers() {
        jdbcTemplate.update("DELETE FROM sys_user");
    }

    private void insertUser(String username, int role) {
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES (?, ?, ?, ?, 1, 0)
                """, username, ENCODER.encode(RAW_PWD), username, role);
    }

    /** 会话（若有）取自请求自身：MockMvc 下它就是被 mock 容器创建的那一个。 */
    private static MockHttpSession createdSession(MvcResult result) {
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    // ------------------------------------------------------- 机制侧：RequestCache 的确会建会话

    /**
     * 「症状来源」的绿灯用例：RequestCache.saveRequest() 建会话，这就是 D-081 的元凶。
     * 与下面两个业务用例成对——同一断言形状（getSession(false)），期望相反。
     */
    @Test
    void requestCacheSaveRequest_createsHttpSession_mechanismOfD081() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/items/search");

        new HttpSessionRequestCache().saveRequest(request, new MockHttpServletResponse());

        assertThat(request.getSession(false))
                .as("HttpSessionRequestCache.saveRequest 内部会 getSession(true)——D-081 的会话泄漏正是它带来的")
                .isNotNull();
    }

    // ------------------------------------------------------- 业务侧：未认证请求必须不建会话

    @Test
    void unauthenticatedProtectedGet_401_andCreatesNoSession() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/items/search"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401001))
                .andReturn();

        assertThat(createdSession(result))
                .as("未认证请求返回 401 时不得创建 HTTP 会话（D-081）")
                .isNull();
    }

    @Test
    void unauthenticatedProtectedPost_401_andCreatesNoSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/items")
                        .contentType("application/json")
                        .content("{\"venueId\":1,\"buyDate\":\"2026-09-30\",\"purchasePrice\":1000}"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(createdSession(result))
                .as("未认证的写请求同样不得创建会话（写路径是放大面的主入口）")
                .isNull();
    }

    @Test
    void unauthenticatedRequests_eachCreatesNoSession_noAccumulation() throws Exception {
        // 逐个采样而非累加计数：MockMvc 每个请求都是独立 mock 容器，与「客户端不接 Cookie
        // 就次次新建」的现场同形——只要有**任何一次**建了会话，泄漏面就成立
        for (int i = 0; i < SAMPLES; i++) {
            MvcResult result = mockMvc.perform(get("/api/stats/system"))
                    .andExpect(status().isUnauthorized())
                    .andReturn();
            assertThat(createdSession(result))
                    .as("第 %d/%d 次未认证请求创建了会话——RequestCache 被重新打开了？", i + 1, SAMPLES)
                    .isNull();
        }
    }

    // ------------------------------------------------------- 对照：已认证请求复用登录会话

    @Test
    void authenticatedRequest_reusesLoginSession_andCreatesNoExtra() throws Exception {
        insertUser("hanako", 2);
        MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/api/auth/login")
                        .param("username", "hanako").param("password", RAW_PWD))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession();

        MvcResult result = mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andReturn();

        // 匿名上下文不建会话（HttpSessionSecurityContextRepository），故 200 路径本来就不会
        // 泄漏——这条用例把「修复只该影响 401 路径」钉住
        assertThat(createdSession(result)).isSameAs(session);
    }
}
