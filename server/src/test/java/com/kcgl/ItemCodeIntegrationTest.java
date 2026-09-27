package com.kcgl;

import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.auth.KcglUserDetails;
import com.kcgl.module.item.ItemEntity;
import com.kcgl.module.itemcode.CreateItemCommand;
import com.kcgl.module.itemcode.ItemCodeService;
import com.kcgl.module.user.SysUserEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理号引擎集成测试（M2-2，docs/01 7.1 唯一定义）：
 * - 取号正确性：同桶递增/月不补零/A99→B1/Z99→AA1 进位/补录旧桶
 * - 前置校验：会场 404003/年代号 404004/档位 404002/停用会场仍可补录（D-031）
 * - 幂等：clientReqId 重放读回原件零新行；键被其他操作占用=重试耗尽 INTERNAL+sys_alert
 * - 并发：16×50 同桶 800 号唯一+计数器==MAX+全员 CREATE 流水+零跳号；两线程建桶竞争
 * - 跳号：预插 uk 冲突行→跳号推进+operation_log 留痕
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@Testcontainers
class ItemCodeIntegrationTest {

    static final LocalDate SEP_2026 = LocalDate.of(2026, 9, 15);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    @Autowired
    ItemCodeService itemCodeService;
    @Autowired
    JdbcTemplate jdbcTemplate;

    long venueId;
    long operatorId;

    @BeforeEach
    void resetFixtures() {
        // operation_log/sys_alert 一并清：跳号与告警断言是跨用例泄漏源（隔离缺口回归）
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM sys_alert");
        jdbcTemplate.update("DELETE FROM stock_ledger");
        jdbcTemplate.update("DELETE FROM item");
        jdbcTemplate.update("DELETE FROM seq_item_code");
        jdbcTemplate.update("DELETE FROM auction_venue");
        jdbcTemplate.update("DELETE FROM price_band");
        jdbcTemplate.update("DELETE FROM year_code");
        jdbcTemplate.update("INSERT INTO year_code(`year`, code) VALUES (2016,'A'),(2026,'K'),(2027,'L')");
        // 单档 X：0 円以上 3000 円未満（左闭右开）
        jdbcTemplate.update("INSERT INTO price_band(code, lower_bound, upper_bound, enabled) VALUES ('X', 0, 3000, 1)");
        jdbcTemplate.update("INSERT INTO auction_venue(code, name, enabled) VALUES ('HT', '飛騨古民具市', 1)");
        venueId = jdbcTemplate.queryForObject("SELECT id FROM auction_venue WHERE code = 'HT'", Long.class);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = 'hase'");
        jdbcTemplate.update("INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd) "
                + "VALUES ('hase', 'x', '早瀬', 2, 1, 0)");
        operatorId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = 'hase'", Long.class);
        runAs(operatorId, "hase");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ 取号正确性

    @Test
    void 同桶连续取号_递增且含价格码() {
        assertThat(create(null, SEP_2026, 1000).getItemCode()).isEqualTo("HTK9-A1X");
        assertThat(create(null, SEP_2026, 1500).getItemCode()).isEqualTo("HTK9-A2X");
        ItemEntity third = create("cr-3", SEP_2026, 2999);
        assertThat(third.getItemCode()).isEqualTo("HTK9-A3X");

        Map<String, Object> counter = bucket();
        assertThat(counter.get("cur_prefix")).isEqualTo("A");
        assertThat(counter.get("cur_seq")).isEqualTo(3);
        // CREATE 流水语义：stock_to=0（在途）、wh 双 NULL（不占仓账）、qty 0、操作人快照
        Map<String, Object> ledger = jdbcTemplate.queryForMap(
                "SELECT stock_to, wh_from, wh_to, qty_change, operator_name, client_req_id "
                        + "FROM stock_ledger WHERE item_id = ?", third.getId());
        assertThat(ledger.get("stock_to")).isEqualTo(0);
        assertThat(ledger.get("wh_from")).isNull();
        assertThat(ledger.get("wh_to")).isNull();
        assertThat(ledger.get("qty_change")).isEqualTo(0);
        assertThat(ledger.get("operator_name")).isEqualTo("早瀬");
        assertThat(ledger.get("client_req_id")).isEqualTo("cr-3");
        // 商品号内快照 + 初始态
        assertThat(third.getVenueCode()).isEqualTo("HT");
        assertThat(third.getYearCode()).isEqualTo("K");
        assertThat(third.getBuyMonth()).isEqualTo(9);
        assertThat(third.getSeqPrefix()).isEqualTo("A");
        assertThat(third.getSeqNo()).isEqualTo(3);
        assertThat(third.getPriceBandCode()).isEqualTo("X");
        assertThat(third.getStockStatus()).isZero();
        assertThat(third.getSaleStatus()).isZero();
        assertThat(count("SELECT COUNT(*) FROM operation_log WHERE action = 'ITEM_CREATE'")).isEqualTo(3);
    }

    @Test
    void 月不补零_单双位月各自成桶() {
        assertThat(create(null, LocalDate.of(2026, 10, 2), 1000).getItemCode()).isEqualTo("HTK10-A1X");
        assertThat(create(null, LocalDate.of(2026, 1, 8), 1000).getItemCode()).isEqualTo("HTK1-A1X");
        assertThat(create(null, LocalDate.of(2026, 12, 30), 1000).getItemCode()).isEqualTo("HTK12-A1X");
        // 三个桶各自独立计数
        assertThat(count("SELECT COUNT(*) FROM seq_item_code")).isEqualTo(3);
    }

    @Test
    void A99满进位B1() {
        seedBucket("A", 99);
        assertThat(create(null, SEP_2026, 1000).getItemCode()).isEqualTo("HTK9-B1X");
        assertThat(create(null, SEP_2026, 1000).getItemCode()).isEqualTo("HTK9-B2X");
    }

    @Test
    void Z99满进位AA1() {
        seedBucket("Z", 99);
        assertThat(create(null, SEP_2026, 1000).getItemCode()).isEqualTo("HTK9-AA1X");
    }

    @Test
    void 补录上月落札_进旧桶独立递增() {
        assertThat(create(null, SEP_2026, 1000).getItemCode()).isEqualTo("HTK9-A1X");
        // 次日补录 8 月落札：进 8 月桶从 1 起，不与 9 月桶串号
        assertThat(create(null, LocalDate.of(2026, 8, 20), 1000).getItemCode()).isEqualTo("HTK8-A1X");
        assertThat(create(null, LocalDate.of(2026, 8, 20), 1000).getItemCode()).isEqualTo("HTK8-A2X");
        Map<String, Object> augBucket = jdbcTemplate.queryForMap(
                "SELECT cur_prefix, cur_seq FROM seq_item_code WHERE venue_id = ? AND `year` = 2026 AND month = 8",
                venueId);
        assertThat(augBucket.get("cur_seq")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 前置校验

    @Test
    void 会场不存在_404003() {
        assertThatThrownBy(() -> create(null, SEP_2026, 1000, 999999L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.VENUE_NOT_FOUND);
        assertThat(count("SELECT COUNT(*) FROM item")).isZero();
    }

    @Test
    void 落札年无年代号_404004() {
        assertThatThrownBy(() -> create(null, LocalDate.of(2015, 7, 1), 1000))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.YEAR_CODE_NOT_FOUND);
    }

    @Test
    void 价格无匹配档位_404002() {
        assertThatThrownBy(() -> create(null, SEP_2026, 5000))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PRICE_BAND_NOT_MATCHED);
    }

    @Test
    void 停用会场仍可补录_历史落札路径() {
        jdbcTemplate.update("UPDATE auction_venue SET enabled = 0 WHERE id = ?", venueId);
        ItemEntity item = create(null, SEP_2026, 1000);
        assertThat(item.getItemCode()).isEqualTo("HTK9-A1X");
    }

    // ------------------------------------------------------------------ 幂等

    @Test
    void clientReqId重放_读回原件零新行() {
        ItemEntity first = create("replay-1", SEP_2026, 1000);
        // 第二次同键（网络超时重放）：返回原商品，不取新号不落新行
        ItemEntity replayed = create("replay-1", SEP_2026, 1000);
        assertThat(replayed.getId()).isEqualTo(first.getId());
        assertThat(replayed.getItemCode()).isEqualTo("HTK9-A1X");
        assertThat(count("SELECT COUNT(*) FROM item")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 1")).isEqualTo(1);
        assertThat(bucket().get("cur_seq")).isEqualTo(1);
    }

    @Test
    void 重放键被其他操作占用_重试耗尽INTERNAL并告警() {
        // 前置：client_req_id 已被一条非 CREATE 流水占用（前端缺陷场景）
        jdbcTemplate.update("INSERT INTO stock_ledger(client_req_id, txn_type, item_id, item_code, "
                + "qty_change, operator_id, operator_name) VALUES ('hijack-key', 3, 1, 'HTK9-A1X', -1, 1, '他操作')");
        assertThatThrownBy(() -> create("hijack-key", SEP_2026, 1000))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.INTERNAL);
        // 三次尝试全部回滚：零残留
        assertThat(count("SELECT COUNT(*) FROM item")).isZero();
        assertThat(count("SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 1")).isZero();
        assertThat(count("SELECT COUNT(*) FROM seq_item_code")).isZero();
        // 告警落库（持久红点，非在线即逝）
        assertThat(count("SELECT COUNT(*) FROM sys_alert WHERE dedup_key = 'item-code-retry-exhausted'"))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ 并发

    @Test
    void 并发16线程50件_800号唯一计数器一致() throws Exception {
        int threads = 16;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int tn = t;
                futures.add(pool.submit(() -> {
                    gate.await();
                    runAs(operatorId, "hase");
                    List<String> codes = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        codes.add(create("cc-" + tn + "-" + i, SEP_2026, 1000).getItemCode());
                    }
                    return codes;
                }));
            }
            gate.countDown();
            Set<String> unique = new HashSet<>();
            for (Future<List<String>> future : futures) {
                unique.addAll(future.get(180, TimeUnit.SECONDS));
            }
            assertThat(unique).hasSize(threads * perThread);

            // 严格连续（单写者纪律下零跳号=确定性断言，非 flaky）：A..H 满 99，I 到 8
            Set<String> expected = new HashSet<>();
            String[] prefixes = {"A", "B", "C", "D", "E", "F", "G", "H"};
            for (String prefix : prefixes) {
                for (int seq = 1; seq <= 99; seq++) {
                    expected.add("HTK9-" + prefix + seq + "X");
                }
            }
            for (int seq = 1; seq <= 8; seq++) {
                expected.add("HTK9-I" + seq + "X");
            }
            assertThat(unique).isEqualTo(expected);

            // 事后一致性三断言（docs/01 十一节并发锚点）
            Map<String, Object> counter = bucket();
            assertThat(counter.get("cur_prefix")).isEqualTo("I");
            assertThat(counter.get("cur_seq")).isEqualTo(8);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM item WHERE seq_prefix = 'I'", Long.class)).isEqualTo(8);
            assertThat(count("SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 1"))
                    .isEqualTo((long) threads * perThread);
            assertThat(count("SELECT COUNT(DISTINCT item_id) FROM stock_ledger WHERE txn_type = 1"))
                    .isEqualTo((long) threads * perThread);
            // 纯引擎竞争下不应有跳号（外部冲突行才触发跳号，见下一用例）
            assertThat(count("SELECT COUNT(*) FROM operation_log WHERE action = 'ITEM_CODE_SKIP'")).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 两线程并发建桶_均成功且计数正确() throws Exception {
        LocalDate may2027 = LocalDate.of(2027, 5, 10);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            Future<String> a = pool.submit(() -> {
                gate.await();
                runAs(operatorId, "hase");
                return create("race-a", may2027, 1000).getItemCode();
            });
            Future<String> b = pool.submit(() -> {
                gate.await();
                runAs(operatorId, "hase");
                return create("race-b", may2027, 1000).getItemCode();
            });
            gate.countDown();
            // 会场 HT + 2027=L + 5 月 → HTL5 桶
            Set<String> codes = Set.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
            assertThat(codes).isEqualTo(Set.of("HTL5-A1X", "HTL5-A2X"));
            Map<String, Object> counter = jdbcTemplate.queryForMap(
                    "SELECT cur_prefix, cur_seq FROM seq_item_code WHERE venue_id = ? AND `year` = 2027 AND month = 5",
                    venueId);
            assertThat(counter.get("cur_prefix")).isEqualTo("A");
            assertThat(counter.get("cur_seq")).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM seq_item_code WHERE venue_id = ? AND `year` = 2027 AND month = 5",
                    venueId)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 跳号

    @Test
    void 预插冲突行_跳号推进并留痕() {
        // 外部路径（历史数据修复等）插入已提交行但未推进计数器：A1 已被占
        jdbcTemplate.update("""
                INSERT INTO item(item_code, venue_id, venue_code, `year`, year_code, buy_month, seq_prefix,
                    seq_no, buy_date, purchase_price, price_band_code, warehouse, created_by)
                VALUES ('HTK9-A1X', ?, 'HT', 2026, 'K', 9, 'A', 1, '2026-09-01', 1000, 'X', 1, ?)
                """, venueId, operatorId);
        seedBucket("A", 0);

        ItemEntity item = create(null, SEP_2026, 1000);
        assertThat(item.getItemCode()).isEqualTo("HTK9-A2X");
        // 计数器同步推进到 2（保持 cur_seq==MAX(seq_no) 自检不变量）
        assertThat(bucket().get("cur_seq")).isEqualTo(2);
        // 跳号留痕：operation_log ITEM_CODE_SKIP，detail 含被跳过的号
        String detail = jdbcTemplate.queryForObject(
                "SELECT detail FROM operation_log WHERE action = 'ITEM_CODE_SKIP'", String.class);
        assertThat(detail).contains("HTK9-A1X");
    }

    // ------------------------------------------------------------------ 工具

    private ItemEntity create(String clientReqId, LocalDate buyDate, long price) {
        return create(clientReqId, buyDate, price, venueId);
    }

    private ItemEntity create(String clientReqId, LocalDate buyDate, long price, long vid) {
        return itemCodeService.create(new CreateItemCommand(
                clientReqId, null, vid, buyDate, null, price, null, null, null,
                1, null, null, null, "連続録入テスト", null, null, null, null, null, null,
                operatorId, "早瀬"));
    }

    private Map<String, Object> bucket() {
        return jdbcTemplate.queryForMap(
                "SELECT cur_prefix, cur_seq FROM seq_item_code WHERE venue_id = ? AND `year` = 2026 AND month = 9",
                venueId);
    }

    private void seedBucket(String prefix, int seq) {
        jdbcTemplate.update("INSERT INTO seq_item_code(venue_id, `year`, month, cur_prefix, cur_seq) "
                + "VALUES (?, 2026, 9, ?, ?)", venueId, prefix, seq);
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    /** 直接调服务层（引擎无 REST 端点，M2-3 接线）：审计读 SecurityContext，需显式装填。 */
    private static void runAs(Long userId, String username) {
        SysUserEntity user = new SysUserEntity();
        user.setId(userId);
        user.setUsername(username);
        user.setPasswordHash("x");
        user.setDisplayName(username);
        user.setRole(2);
        user.setEnabled(1);
        user.setMustChangePwd(0);
        user.setLocale("ja-JP");
        KcglUserDetails details = KcglUserDetails.of(user, Clock.systemDefaultZone());
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())));
    }
}
