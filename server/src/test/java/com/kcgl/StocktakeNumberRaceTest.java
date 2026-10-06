package com.kcgl;

import com.kcgl.module.auth.KcglUserDetails;
import com.kcgl.module.user.SysUserEntity;
import com.kcgl.module.stocktake.StocktakeService;
import com.kcgl.module.stocktake.dto.StocktakeCreateRequest;
import com.kcgl.module.stocktake.dto.StocktakeSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归门（审计项 A2）：单号生成能否在**空表**上被并发发起撞出重复或失败。
 *
 * <p>缺陷机理：单号计数器是"全表当日前缀计数"（{@code selectCount(likeRight(stocktakeNo, "PD"+date+"-"))}），
 * 而行锁 {@code lockActiveIds(warehouse)} 是**按仓**的。两个不同仓的并发发起互不排斥（都是合法的：
 * 一仓一进行中单互不影响），于是两者都可能数到 0 并各自铸造「PD+日期+01」。造单点 = 空表
 * （首次部署 / 恢复 / 截断后），此时 {@code SELECT ... FOR UPDATE} 在空区间只拿到**相容的间隙锁**，
 * 两个事务都能通过计数，随后在 {@code INSERT INTO stocktake} 的插入意向锁上互斥而死锁。
 *
 * <p><b>RED 取证（2026-10-07，修复前，夹具已修）</b>：12 轮中 4 轮失败（round 0/1/8/10），败者一律
 * {@code DeadlockLoserDataAccessException}，死锁点固定在 {@code INSERT INTO stocktake}；其余 8 轮
 * 两线程未在间隙锁上重叠，后到者的一致读（{@code selectCount}）已看到先提交的行，各自取得
 * -01/-02 而成功。即 A2 成立：
 * 空表上的跨仓并发发起不是"可能撞号"，而是"命中窗口即整单失败"（实测 4/12 轮）。详见 {@code docs/qa/深度审计报告.md}。
 *
 * <p><b>修复（D-111）</b>：{@code StocktakeService.create} 改为事务边界之外的并发类异常重试
 * （死锁败者/锁等待超时/单号 uk 冲突，业务异常原样上抛），与本类 close/scan 的 C1 纪律及管理号
 * 引擎同形：败者换新事务重进时表已有行，全扫即锁到实记录，按仓探测与当日计数才真正串行化。
 * 修复后同一夹具 12 轮全绿，日志记录 2 次 {@code DeadlockLoserDataAccessException} 重试
 * （{@code attempt=1/3}），无重试耗尽、无告警——败者确实走了重试路径，而非侥幸避让。
 *
 * <p>取证方法已消除混淆项：早期版本直接调服务层而未装填 SecurityContext，导致赢家一侧**每轮必抛**
 * {@code 审计记录缺少认证上下文}，曾据此误记为「12/12 轮失败」——那是**夹具缺陷**而非竞态。
 * 现每线程显式装填与 {@code ExcelIntegrationTest#runAs} 同形的认证上下文，失败只可能来自死锁，
 * 修复前实测的真实 RED 为 4/12 轮。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class StocktakeNumberRaceTest {

    private static final int ROUNDS = 12;

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    @Autowired
    StocktakeService stocktakeService;
    @Autowired
    JdbcTemplate jdbcTemplate;

    long operatorId;

    @BeforeEach
    void resetFixtures() {
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM stocktake_diff");
        jdbcTemplate.update("DELETE FROM stocktake_scan");
        jdbcTemplate.update("DELETE FROM stocktake");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = 'race-op'");
        jdbcTemplate.update(
                "INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd) "
                        + "VALUES ('race-op', 'x', '并发取证', 1, 1, 0)");
        operatorId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'race-op'", Long.class);
    }

    @Test
    @Tag("regression")
    void concurrentCreateOnEmptyTable_mintsDistinctNumbers() throws Exception {
        List<String> failures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                jdbcTemplate.update("DELETE FROM stocktake");

                CyclicBarrier barrier = new CyclicBarrier(2);
                AtomicReference<String> noA = new AtomicReference<>();
                AtomicReference<String> noB = new AtomicReference<>();
                AtomicReference<String> errA = new AtomicReference<>();
                AtomicReference<String> errB = new AtomicReference<>();

                Future<?> fA = pool.submit(() -> runCreate(1, barrier, noA, errA));
                Future<?> fB = pool.submit(() -> runCreate(2, barrier, noB, errB));
                fA.get(30, TimeUnit.SECONDS);
                fB.get(30, TimeUnit.SECONDS);

                if (errA.get() != null || errB.get() != null) {
                    failures.add("round " + round + ": 仓1 err=" + errA.get() + " 仓2 err=" + errB.get());
                } else if (noA.get() != null && noA.get().equals(noB.get())) {
                    failures.add("round " + round + ": 撞号 " + noA.get() + " == " + noB.get());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures)
                .as("空表上并发发起应各自成功且单号互异（A2：出现以下任一即为缺陷）")
                .isEmpty();
    }

    private void runCreate(int warehouse, CyclicBarrier barrier,
            AtomicReference<String> no, AtomicReference<String> err) {
        try {
            installSecurityContext(operatorId, "race-op");
            barrier.await(10, TimeUnit.SECONDS);
            StocktakeSummaryResponse res =
                    stocktakeService.create(new StocktakeCreateRequest(warehouse), operatorId);
            no.set(res.stocktakeNo());
        } catch (Exception e) {
            err.set(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** 与 ExcelIntegrationTest#runAs 同形：审计读 SecurityContext，worker 线程须各自装填。 */
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
