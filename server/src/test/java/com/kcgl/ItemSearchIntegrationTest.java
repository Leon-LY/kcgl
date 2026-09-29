package com.kcgl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Clock;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局搜索集成测试（M5-①，D-061/D-062/D-065）：
 * - kw 优先级链：管理号整串（NFKC+大写快路径）＞日期双格式（yyyy-M-d / yyyy/M/d）＞模糊 LIKE
 * - LIKE ESCAPE：50% 不当通配符（验收 12 字面）；假名 collation 现实（ア/ぁ 检索
 *   命中 あ——utf8mb4_0900_ai_ci 平/片假名+小仮名同权重，契约而非意外）
 * - 筛选：仓库/库存态/销售态/会场/落札日区间 组合
 * - 滞销（A14/D-065）：黄红级别 Java 派生 + warnLevel SQL 谓词同源；sys_setting 阈值覆写
 *   + 脏值防御回退；排除 作废/软删/成交/在途
 * - 分页 size≤100 钳制；viewer 可搜（A19 成本利润随行）
 *
 * <p>夹具直插 item 行（精确控制 stock/sale/入库日——经真实端点构造需多跳动作，
 * 与被测搜索谓词无关）；today 经注入 Clock（JST）。
 */
@SpringBootTest(properties = "kcgl.security.allowed-origins=https://kcgl.example.com")
@AutoConfigureMockMvc
@Testcontainers
class ItemSearchIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String PASSWORD = "Src-1234-x";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    Clock clock;

    long bossId;
    long htVenueId;

    @BeforeEach
    void resetFixtures() {
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM stock_ledger");
        jdbcTemplate.update("DELETE FROM item");
        jdbcTemplate.update("DELETE FROM seq_item_code");
        jdbcTemplate.update("DELETE FROM sys_setting");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN ('boss','eichi','miru')");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('boss', ?, '管理者', 1, 1, 0), ('eichi', ?, '編集者', 2, 1, 0), ('miru', ?, '閲覧者', 3, 1, 0)
                """, ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD));
        bossId = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = 'boss'", Long.class);
        jdbcTemplate.update("DELETE FROM auction_venue");
        jdbcTemplate.update("DELETE FROM price_band");
        jdbcTemplate.update("DELETE FROM year_code");
        jdbcTemplate.update("INSERT INTO year_code(`year`, code) VALUES (2016,'A'),(2026,'K')");
        jdbcTemplate.update(
                "INSERT INTO price_band(code, lower_bound, upper_bound, enabled) VALUES ('X', 0, 3000, 1), ('Y', 3000, 1000000, 1)");
        jdbcTemplate.update(
                "INSERT INTO auction_venue(code, name, enabled) VALUES ('HT', '飛騨古民具市', 1), ('NR', '名古屋リサイクル市', 1)");
        htVenueId = jdbcTemplate.queryForObject("SELECT id FROM auction_venue WHERE code = 'HT'", Long.class);
    }

    // ------------------------------------------------------------- 夹具与工具

    private MockHttpSession loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .param("username", username).param("password", PASSWORD))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("登录应成功: %s",
                result.getResponse().getContentAsString()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession();
    }

    /** 直插商品行：精确控制库存/销售/入库日状态（搜索谓词的被测对象）。 */
    private long insertItem(String code, String venueCode, LocalDate buyDate, long price,
            int warehouse, int stockStatus, int saleStatus, LocalDate inDate, String remark) {
        Long venueId = jdbcTemplate.queryForObject(
                "SELECT id FROM auction_venue WHERE code = ?", Long.class, venueCode);
        jdbcTemplate.update("""
                INSERT INTO item(item_code, venue_id, venue_code, `year`, year_code, buy_month,
                  seq_prefix, seq_no, buy_date, purchase_price, price_band_code, warehouse,
                  stock_status, sale_status, voided, deleted, created_by, remark, warehouse_in_date)
                VALUES (?, ?, ?, ?, 'K', ?, 'A', 1, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?)
                """,
                code, venueId, venueCode, buyDate.getYear(), buyDate.getMonthValue(),
                buyDate, price, price <= 3000 ? "X" : "Y", warehouse, stockStatus, saleStatus,
                bossId, remark, inDate);
        return jdbcTemplate.queryForObject("SELECT id FROM item WHERE item_code = ?", Long.class, code);
    }

    // ------------------------------------------------------------- kw 优先级链

    @Test
    void search_kwExactItemCode_takesFastPathWithFullWidthNormalization() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        insertItem("HTK9-A1X", "HT", LocalDate.of(2026, 9, 15), 1000, 1, 0, 0, null, null);
        insertItem("NRK9-A1X", "NR", LocalDate.of(2026, 9, 15), 1000, 1, 0, 0, null, null);

        mockMvc.perform(get("/api/items/search").param("kw", "HTK9-A1X").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].itemCode").value("HTK9-A1X"));
        // 全角+小写（手输兜底，7.8）：NFKC+大写后命中快路径
        mockMvc.perform(get("/api/items/search").param("kw", "ｈｔｋ９－ａ１ｘ").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].itemCode").value("HTK9-A1X"));
    }

    @Test
    void search_partialCode_fallsBackToLikeContainment() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        insertItem("HTK9-A1X", "HT", LocalDate.of(2026, 9, 15), 1000, 1, 0, 0, null, null);
        insertItem("HTK9-A2X", "HT", LocalDate.of(2026, 9, 16), 1000, 1, 0, 0, null, null);
        insertItem("NRK9-A1X", "NR", LocalDate.of(2026, 9, 15), 1000, 1, 0, 0, null, null);

        // 部分码不完整命中管理号正则 → LIKE 包含（A1X/A1Y 两级语义自然衔接）
        mockMvc.perform(get("/api/items/search").param("kw", "HTK9-A1").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].itemCode").value("HTK9-A1X"));
        // 短串跨会场包含命中
        mockMvc.perform(get("/api/items/search").param("kw", "A1X").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.rows[*].itemCode",
                        containsInAnyOrder("HTK9-A1X", "NRK9-A1X")));
    }

    @Test
    void search_dateKw_matchesBuyDateInBothFormats() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        insertItem("HTK9-A1X", "HT", LocalDate.of(2026, 1, 15), 1000, 1, 0, 0, null, null);
        insertItem("HTK9-A2X", "HT", LocalDate.of(2026, 1, 16), 1000, 1, 0, 0, null, null);

        mockMvc.perform(get("/api/items/search").param("kw", "2026-01-15").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].itemCode").value("HTK9-A1X"));
        // 单数位月日（LocalDate.parse 不支持——正则捕获后 of 构造）
        mockMvc.perform(get("/api/items/search").param("kw", "2026/1/15").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].itemCode").value("HTK9-A1X"));
    }

    @Test
    void search_venueNameKw_resolvesToVenueIds() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        insertItem("HTK9-A1X", "HT", LocalDate.of(2026, 9, 15), 1000, 1, 0, 0, null, null);
        insertItem("HTK9-A2X", "HT", LocalDate.of(2026, 9, 16), 1000, 1, 0, 0, null, null);
        insertItem("NRK9-A1X", "NR", LocalDate.of(2026, 9, 15), 1000, 1, 0, 0, null, null);

        mockMvc.perform(get("/api/items/search").param("kw", "飛騨").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.rows[*].itemCode",
                        containsInAnyOrder("HTK9-A1X", "HTK9-A2X")));
        mockMvc.perform(get("/api/items/search").param("kw", "リサイクル").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].itemCode").value("NRK9-A1X"));
    }

    @Test
    void search_fuzzyColumns_remarkShelfGroupItemNameCategoryAuthorKiln() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        insertItem("HTK9-A1X", "HT", LocalDate.of(2026, 9, 15), 1000, 1, 0, 0, null, "木製椅子");
        insertItem("HTK9-A2X", "HT", LocalDate.of(2026, 9, 16), 1000, 1, 0, 0, null, null);
        jdbcTemplate.update("UPDATE item SET shelf_no = 'S-12' WHERE item_code = 'HTK9-A2X'");
        insertItem("HTK9-A3X", "HT", LocalDate.of(2026, 9, 17), 1000, 1, 0, 0, null, null);
        jdbcTemplate.update("UPDATE item SET group_no = 'G7' WHERE item_code = 'HTK9-A3X'");
        insertItem("HTK9-A4X", "HT", LocalDate.of(2026, 9, 18), 1000, 1, 0, 0, null, null);
        jdbcTemplate.update("UPDATE item SET item_name = '伊万里焼大皿' WHERE item_code = 'HTK9-A4X'");
        insertItem("HTK9-A5X", "HT", LocalDate.of(2026, 9, 19), 1000, 1, 0, 0, null, null);
        jdbcTemplate.update("UPDATE item SET category = '陶磁器' WHERE item_code = 'HTK9-A5X'");
        insertItem("HTK9-A6X", "HT", LocalDate.of(2026, 9, 20), 1000, 1, 0, 0, null, null);
        jdbcTemplate.update("UPDATE item SET author_kiln = '九谷焼' WHERE item_code = 'HTK9-A6X'");

        String[][] cases = {
                {"木製椅子", "HTK9-A1X"},
                {"S-12", "HTK9-A2X"},
                {"G7", "HTK9-A3X"},
                {"伊万里", "HTK9-A4X"},
                {"陶磁器", "HTK9-A5X"},
                {"九谷", "HTK9-A6X"},
        };
        for (String[] c : cases) {
            mockMvc.perform(get("/api/items/search").param("kw", c[0]).session(editor))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.rows[0].itemCode").value(c[1]));
        }
    }

    @Test
    void search_likeEscape_percentAndUnderscoreAreLiteral() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        insertItem("HTK9-A1X", "HT", LocalDate.of(2026, 9, 15), 1000, 1, 0, 0, null, "50%オフ");
        insertItem("HTK9-A2X", "HT", LocalDate.of(2026, 9, 16), 1000, 1, 0, 0, null, "定価5000円");
        insertItem("HTK9-A3X", "HT", LocalDate.of(2026, 9, 17), 1000, 1, 0, 0, null, "棚_雑貨");

        // 50% 不得当通配符：不转义时 LIKE '%50%%' 会命中含「50」的 A2X（验收 12 字面）
        mockMvc.perform(get("/api/items/search").param("kw", "50%").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].itemCode").value("HTK9-A1X"));
        // _ 同理是字面下划线（不转义会命中任意单字符）
        mockMvc.perform(get("/api/items/search").param("kw", "棚_").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].itemCode").value("HTK9-A3X"));
        mockMvc.perform(get("/api/items/search").param("kw", "5000").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].itemCode").value("HTK9-A2X"));
    }

    /**
     * 假名 collation 现实（D-062 4 修正）：utf8mb4_0900_ai_ci 平/片假名 AND
     * 小仮名/通常仮名同权重——カタカナ检索命中平假名数据、小仮名命中通常仮名
     * 均为接受的宽松匹配（搜到更多是净收益方向）。首版假设「ぁ≠あ 按形匹配」
     * 被 MySQL 8.4 实测推翻（0900 _ai_ 系把小仮名差异也折叠；仅 _ks 系区分）——
     * 帮助文案如实写「かなの種類は区別されません」。负向对照：不同假名不命中
     * （宽松折叠≠任意匹配）。
     */
    @Test
    void search_kanaCollation_kanaVariantsFoldLoosely() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        insertItem("HTK9-A1X", "HT", LocalDate.of(2026, 9, 15), 1000, 1, 0, 0, null, "あいうえお");

        mockMvc.perform(get("/api/items/search").param("kw", "アイウエオ").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].itemCode").value("HTK9-A1X"));
        mockMvc.perform(get("/api/items/search").param("kw", "ぁいうえお").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].itemCode").value("HTK9-A1X"));
        mockMvc.perform(get("/api/items/search").param("kw", "かきくけこ").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    // ------------------------------------------------------------- 筛选与排除

    @Test
    void search_filters_combineByAnd() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        insertItem("HTK9-A1X", "HT", LocalDate.of(2026, 1, 10), 1000, 1, 1, 0, null, null);
        insertItem("HTK9-A2X", "HT", LocalDate.of(2026, 1, 20), 1000, 2, 1, 1, null, null);
        insertItem("NRK9-A1X", "NR", LocalDate.of(2026, 2, 5), 1000, 1, 0, 0, null, null);
        insertItem("HTK9-A3X", "HT", LocalDate.of(2026, 3, 15), 1000, 1, 1, 0, null, null);

        mockMvc.perform(get("/api/items/search").param("warehouse", "2").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[*].itemCode", containsInAnyOrder("HTK9-A2X")));
        mockMvc.perform(get("/api/items/search").param("stockStatus", "0").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[*].itemCode", containsInAnyOrder("NRK9-A1X")));
        mockMvc.perform(get("/api/items/search").param("saleStatus", "1").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[*].itemCode", containsInAnyOrder("HTK9-A2X")));
        mockMvc.perform(get("/api/items/search").param("venueId", String.valueOf(htVenueId)).session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[*].itemCode",
                        containsInAnyOrder("HTK9-A1X", "HTK9-A2X", "HTK9-A3X")));
        mockMvc.perform(get("/api/items/search")
                        .param("buyDateFrom", "2026-01-15").param("buyDateTo", "2026-02-28")
                        .session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[*].itemCode",
                        containsInAnyOrder("HTK9-A2X", "NRK9-A1X")));
        // 组合=AND
        mockMvc.perform(get("/api/items/search")
                        .param("warehouse", "1").param("stockStatus", "1").param("saleStatus", "0")
                        .param("venueId", String.valueOf(htVenueId))
                        .param("buyDateFrom", "2026-01-01").param("buyDateTo", "2026-12-31")
                        .session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[*].itemCode",
                        containsInAnyOrder("HTK9-A1X", "HTK9-A3X")));
    }

    @Test
    void search_excludesVoidedAndDeletedItems() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        insertItem("HTK9-A1X", "HT", LocalDate.of(2026, 9, 15), 1000, 1, 0, 0, null, "対象");
        insertItem("HTK9-A2X", "HT", LocalDate.of(2026, 9, 16), 1000, 1, 0, 0, null, "対象");
        insertItem("HTK9-A3X", "HT", LocalDate.of(2026, 9, 17), 1000, 1, 0, 0, null, "対象");
        jdbcTemplate.update("UPDATE item SET voided = 1 WHERE item_code = 'HTK9-A2X'");
        jdbcTemplate.update("UPDATE item SET deleted = 1 WHERE item_code = 'HTK9-A3X'");

        mockMvc.perform(get("/api/items/search").param("kw", "対象").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].itemCode").value("HTK9-A1X"));
    }

    // ------------------------------------------------------------- 滞销（D-065）

    @Test
    void search_slowMove_levelsDerivedAndFilterSharesBoundary() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        LocalDate today = LocalDate.now(clock);
        // S1 黄（35d）、S2 红·在售滞销（95d）、S3 成交排除、S4 在途无入库日、
        // S5 未满黄线（29d）、S6 红边界（90d）、S7 已出库排除
        insertItem("HTK9-S1X", "HT", LocalDate.of(2026, 9, 15), 1000, 1, 1, 0, today.minusDays(35), null);
        insertItem("HTK9-S2X", "HT", LocalDate.of(2026, 9, 15), 1000, 1, 1, 1, today.minusDays(95), null);
        insertItem("HTK9-S3X", "HT", LocalDate.of(2026, 9, 15), 1000, 1, 1, 2, today.minusDays(95), null);
        insertItem("HTK9-S4X", "HT", LocalDate.of(2026, 9, 15), 1000, 1, 0, 0, null, null);
        insertItem("HTK9-S5X", "HT", LocalDate.of(2026, 9, 15), 1000, 1, 1, 0, today.minusDays(29), null);
        insertItem("HTK9-S6X", "HT", LocalDate.of(2026, 9, 15), 1000, 1, 1, 0, today.minusDays(90), null);
        insertItem("HTK9-S7X", "HT", LocalDate.of(2026, 9, 15), 1000, 1, 2, 2, today.minusDays(95), null);

        // 行级别徽标：kw 快路径单件断言（默认 30/90）
        String[][] levels = {
                {"HTK9-S1X", "1"}, {"HTK9-S2X", "2"}, {"HTK9-S3X", "0"}, {"HTK9-S4X", "0"},
                {"HTK9-S5X", "0"}, {"HTK9-S6X", "2"}, {"HTK9-S7X", "0"},
        };
        for (String[] c : levels) {
            mockMvc.perform(get("/api/items/search").param("kw", c[0]).session(editor))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.rows[0].slowMoveLevel").value(Integer.parseInt(c[1])));
        }

        // warnLevel 筛选（SQL 谓词与行徽标同一边界计算）：1=黄+红、2=仅红
        mockMvc.perform(get("/api/items/search").param("warnLevel", "1").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[*].itemCode",
                        containsInAnyOrder("HTK9-S1X", "HTK9-S2X", "HTK9-S6X")));
        mockMvc.perform(get("/api/items/search").param("warnLevel", "2").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[*].itemCode",
                        containsInAnyOrder("HTK9-S2X", "HTK9-S6X")));
    }

    @Test
    void search_slowMoveThresholds_sysSettingOverrideAndDirtyValueFallback() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        LocalDate today = LocalDate.now(clock);
        insertItem("HTK9-S1X", "HT", LocalDate.of(2026, 9, 15), 1000, 1, 1, 0, today.minusDays(35), null);
        insertItem("HTK9-S2X", "HT", LocalDate.of(2026, 9, 15), 1000, 1, 1, 1, today.minusDays(95), null);

        // 覆写阈值 60/120：35d→0、95d→黄
        jdbcTemplate.update(
                "INSERT INTO sys_setting(`key`, `value`) VALUES ('slow_move.warn_days','60'),('slow_move.alarm_days','120')");
        mockMvc.perform(get("/api/items/search").param("kw", "HTK9-S1X").session(editor))
                .andExpect(jsonPath("$.data.rows[0].slowMoveLevel").value(0));
        mockMvc.perform(get("/api/items/search").param("kw", "HTK9-S2X").session(editor))
                .andExpect(jsonPath("$.data.rows[0].slowMoveLevel").value(1));

        // 脏值防御：解析失败回退默认 30/90（D-065）
        jdbcTemplate.update("UPDATE sys_setting SET `value` = 'abc' WHERE `key` = 'slow_move.warn_days'");
        mockMvc.perform(get("/api/items/search").param("kw", "HTK9-S1X").session(editor))
                .andExpect(jsonPath("$.data.rows[0].slowMoveLevel").value(1));
    }

    // ------------------------------------------------------------- 分页与角色

    @Test
    void search_pagination_clampsSizeAndViewerCanSearch() throws Exception {
        MockHttpSession viewer = loginAs("miru");
        insertItem("HTK9-A1X", "HT", LocalDate.of(2026, 9, 15), 1000, 1, 0, 0, null, null);
        insertItem("HTK9-A2X", "HT", LocalDate.of(2026, 9, 16), 1000, 1, 0, 0, null, null);
        insertItem("HTK9-A3X", "HT", LocalDate.of(2026, 9, 17), 1000, 1, 0, 0, null, null);

        mockMvc.perform(get("/api/items/search").param("page", "1").param("size", "2").session(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.rows.length()").value(2));
        mockMvc.perform(get("/api/items/search").param("page", "2").param("size", "2").session(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(1));
        // size 上限 100 钳制（响应回显钳后值）
        mockMvc.perform(get("/api/items/search").param("size", "500").session(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100))
                .andExpect(jsonPath("$.data.rows.length()").value(3));
        // 无 kw 无筛选=浏览态：viewer 可搜（A19 成本利润随行）
        mockMvc.perform(get("/api/items/search").session(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.rows[0].purchasePrice").value(1000))
                .andExpect(jsonPath("$.data.rows[0].totalCost").value(1000));
    }
}
