package com.kcgl;

import com.kcgl.module.auth.KcglUserDetails;
import com.kcgl.module.inventory.InventoryActionService;
import com.kcgl.module.inventory.LedgerConsistencyService;
import com.kcgl.module.inventory.dto.SellRequest;
import com.kcgl.module.item.ItemMapper;
import com.kcgl.module.item.RecycleService;
import com.kcgl.module.item.dto.RecycleActionRequest;
import com.kcgl.module.user.SysUserEntity;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 回收站软删与库存动作的**并发交错**（审计项 A1）。
 *
 * <p>缺陷机理：软删在事务内先 {@code selectById} 取快照（REPEATABLE READ 下事务内读到的是快照），
 * 再据该快照 {@code buildLedger} 记账。若期间他事务（售出/编辑/作废）已提交改了
 * {@code stock_status}，而条件更新只判 {@code deleted}（售出不动 deleted），更新照样命中 1 行、
 * 遂按**旧态**记出幻影头寸：在库件被并发售出后本应记 0，却按快照记 −1 → 该件同仓净头寸 −1，
 * 破「每仓 Σ(流水双向入账) ≡ COUNT(在库未删未废)」不变量（{@link LedgerConsistencyService}）。
 *
 * <p>确定性编排（不靠 sleep 撞时机）：{@link ItemMapper} 经 {@link GateConfig} 包一层 JDK 动态代理，
 * 让软删线程**在事务内读完快照后停住**，主线程趁机把售出跑完提交（售出走乐观锁，version+1），
 * 再放行软删继续。修复前条件更新无 version 守卫 → 更新命中 → 按旧态记账（RED）；
 * 修复后 version 不符 → 0 行 → 换新事务重读重试 → 按现态（已出库）记 0（GREEN）。
 *
 * <p>闸门按**线程身份**判定（只拦软删线程的取件），故夹具期（建件/到货）在主线程上的读不受影响。
 * 竞态走 service 层直调而非 MockMvc：worker 线程各自装填认证上下文（审计读 SecurityContext），
 * 与 {@code StocktakeNumberRaceTest} 同形，避开跨线程共享 MockHttpSession 的干扰项。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class RecycleConcurrencyTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String PASSWORD = "Race-1234-x";

    /** 售出后 stock_status=已出库（0 在途 / 1 在库 / 2 已出库）。 */
    static final int SHIPPED = 2;
    static final int RECYCLE_DELETE_TXN = 13;
    static final long GATE_TIMEOUT_SECONDS = 30;

    /** 只拦这个线程的取件（软删线程）；主线程上的夹具读不受影响。 */
    static volatile Thread gatedThread;
    static final AtomicBoolean gatedOnce = new AtomicBoolean(false);
    static final CountDownLatch snapshotRead = new CountDownLatch(1);
    static final CountDownLatch sellCommitted = new CountDownLatch(1);

    /**
     * 包一层 ItemMapper：取件后（真实读已返回旧快照）若来自软删线程则停住并交还控制权。
     * 用 JDK 动态代理而非 Mockito spy——接口 mock 上 {@code invocation.callRealMethod()}
     * 会抛 "Cannot call abstract real method on java object"，无法在放行后走真实实现。
     */
    @TestConfiguration
    static class GateConfig {
        @Bean
        static BeanPostProcessor itemMapperGate() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof ItemMapper real)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(ItemMapper.class.getClassLoader(),
                            new Class<?>[]{ItemMapper.class}, (proxy, method, args) -> {
                                Object result;
                                try {
                                    result = method.invoke(real, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                                if ("selectById".equals(method.getName())
                                        && Thread.currentThread() == gatedThread
                                        && gatedOnce.compareAndSet(false, true)) {
                                    snapshotRead.countDown();
                                    if (!sellCommitted.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("并发售出未在 "
                                                + GATE_TIMEOUT_SECONDS + "s 内返回");
                                    }
                                }
                                return result;
                            });
                }
            };
        }
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    LedgerConsistencyService consistencyService;
    @Autowired
    RecycleService recycleService;
    @Autowired
    InventoryActionService inventoryActionService;

    long bossId;
    long htVenueId;

    @BeforeEach
    void resetFixtures() {
        gatedThread = null;
        gatedOnce.set(false);
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM yahoo_listing");
        jdbcTemplate.update("DELETE FROM stock_ledger");
        jdbcTemplate.update("DELETE FROM item");
        jdbcTemplate.update("DELETE FROM seq_item_code");
        jdbcTemplate.update("DELETE FROM sys_setting");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = 'boss'");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('boss', ?, '管理者', 1, 1, 0)
                """, ENCODER.encode(PASSWORD));
        bossId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = 'boss'", Long.class);
        jdbcTemplate.update("DELETE FROM auction_venue");
        jdbcTemplate.update("DELETE FROM price_band");
        jdbcTemplate.update(
                "INSERT INTO price_band(code, lower_bound, upper_bound, enabled) VALUES ('X', 0, 3000, 1), ('Y', 3000, 1000000, 1)");
        jdbcTemplate.update(
                "INSERT INTO auction_venue(code, name, enabled) VALUES ('HT', '飛騨古民具市', 1)");
        htVenueId = jdbcTemplate.queryForObject("SELECT id FROM auction_venue WHERE code = 'HT'", Long.class);
    }

    @Test
    @Tag("regression")
    void recycleDelete_racingSell_recordsLedgerFromFreshStateNotStaleSnapshot() throws Exception {
        MockHttpSession boss = loginAs("boss");
        long id = createItem(boss, "c-race", htVenueId, 1000, 1);
        arrive(boss, id, "arr-race", 1);

        AtomicReference<Throwable> recycleErr = new AtomicReference<>();
        Thread recycler = new Thread(() -> {
            installSecurityContext(bossId, "boss");
            try {
                recycleService.delete(id, new RecycleActionRequest("del-race", "整理"), bossId, "boss");
            } catch (Throwable t) {
                recycleErr.set(t);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }, "recycle-thread");
        gatedThread = recycler;
        recycler.start();

        if (!snapshotRead.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("软删未在事务内读到商品快照；recycleErr=" + recycleErr.get(),
                    recycleErr.get());
        }
        installSecurityContext(bossId, "boss");
        try {
            inventoryActionService.sell(new SellRequest(id, "sell-race", 5000L), bossId, "boss");
        } finally {
            sellCommitted.countDown();
            SecurityContextHolder.clearContext();
        }
        recycler.join(TimeUnit.SECONDS.toMillis(GATE_TIMEOUT_SECONDS));

        // 软断言：缺陷态下一次性暴露全部证据（字段级 + 不变量级），不必逐个撞
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(recycleErr.get()).as("软删不应失败").isNull();

        // 软删流水必须按**售出后**的现态记账：已出库件记 0
        Map<String, Object> ledger = jdbcTemplate.queryForMap(
                "SELECT txn_type, wh_from, wh_to, qty_change, stock_from, stock_to, reason "
                        + "FROM stock_ledger WHERE client_req_id = 'del-race'");
        softly.assertThat(num(ledger, "txn_type")).isEqualTo(RECYCLE_DELETE_TXN);
        softly.assertThat(num(ledger, "stock_from")).as("应按现态（已出库 2）记账，而非快照态（在库 1）")
                .isEqualTo(SHIPPED);
        softly.assertThat(ledger.get("wh_from")).as("已出库件不占仓，wh_from 必须为空").isNull();
        softly.assertThat(num(ledger, "qty_change")).as("已出库件软删记 0（据旧快照记 −1 即幻影扣减）")
                .isEqualTo(0);

        softly.assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM item WHERE id = ?", Integer.class, id))
                .as("软删本身仍应生效").isEqualTo(1);
        softly.assertThat(consistencyService.check().ok())
                .as("并发售出与软删交错后，Σledger≡COUNT 不变量仍须成立")
                .isTrue();
        softly.assertAll();
    }

    // ------------------------------------------------------------------ 夹具与工具

    private MockHttpSession loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .param("username", username).param("password", PASSWORD))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("登录应成功: %s",
                result.getResponse().getContentAsString()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession();
    }

    private long createItem(MockHttpSession session, String clientReqId, long venueId, long price,
            int warehouse) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/items").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"clientReqId\":\"" + clientReqId + "\",\"venueId\":" + venueId
                                + ",\"buyDate\":\"2026-09-15\",\"purchasePrice\":" + price
                                + ",\"warehouse\":" + warehouse + "}")))
                .andExpect(status().isOk())
                .andReturn();
        return Long.parseLong(result.getResponse().getContentAsString()
                .replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private void arrive(MockHttpSession session, long id, String clientReqId, int warehouse) throws Exception {
        mockMvc.perform(post("/api/inventory/arrivals").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"items\":[{\"itemId\":" + id + ",\"clientReqId\":\"" + clientReqId
                                + "\",\"warehouse\":" + warehouse + "}],\"warehouseInDate\":\"2026-09-01\"}")))
                .andExpect(status().isOk());
    }

    private long num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    /** 与 StocktakeNumberRaceTest#installSecurityContext 同形：审计读 SecurityContext，worker 线程须各自装填。 */
    private static void installSecurityContext(long userId, String username) {
        SysUserEntity user = new SysUserEntity();
        user.setId(userId);
        user.setUsername(username);
        user.setPasswordHash("x");
        user.setDisplayName(username);
        user.setRole(1);
        user.setEnabled(1);
        user.setMustChangePwd(0);
        user.setLocale("ja-JP");
        KcglUserDetails details = KcglUserDetails.of(user, Clock.systemDefaultZone());
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())));
    }
}
