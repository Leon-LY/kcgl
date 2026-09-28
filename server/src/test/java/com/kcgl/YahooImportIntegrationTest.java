package com.kcgl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 雅虎 CSV 管线集成测试（M4-③，docs/01 7.4 全链路）：
 * - 异步全管线：MS932 multipart→批次 processing→轮询终态→计数/编码/报告
 * - 商品侧标记：LIST_UP / SOLD_MARK（0→2 直报边）携带 CSV 成交价+台账幂等键
 * - 单调不回退：陈旧批次（旧事件时刻的出品中）不得回退成交态/价格
 * - 重上 3→1（CANCEL_MARK 例外边）；unmatched 行 raw_item_code 原文保留
 * - sha 重复 409；乱码字节→异步批次失败（非上传 400）；错误行采样
 * - 角色矩阵：上传 E+，报告/对账/出荷待ち全员
 * - 出荷待ち+对账三视图拼装（含滞留红标）
 */
@SpringBootTest(properties = {
        "kcgl.security.allowed-origins=https://kcgl.example.com",
        "kcgl.yahoo.imports-dir=target/yahoo-test-imports"})
@AutoConfigureMockMvc
@Testcontainers
class YahooImportIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String PASSWORD = "Yho-1234-x";
    static final Charset MS932 = Charset.forName("MS932");

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;

    long venueId;

    @BeforeEach
    void resetFixtures() {
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM stock_ledger");
        jdbcTemplate.update("DELETE FROM yahoo_listing");
        jdbcTemplate.update("DELETE FROM yahoo_import_batch");
        jdbcTemplate.update("DELETE FROM item");
        jdbcTemplate.update("DELETE FROM seq_item_code");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN ('boss','eichi','miru')");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('boss', ?, '管理者', 1, 1, 0), ('eichi', ?, '編集者', 2, 1, 0), ('miru', ?, '閲覧者', 3, 1, 0)
                """, ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD));
        jdbcTemplate.update("DELETE FROM auction_venue");
        jdbcTemplate.update("DELETE FROM price_band");
        jdbcTemplate.update("DELETE FROM year_code");
        jdbcTemplate.update("INSERT INTO year_code(`year`, code) VALUES (2016,'A'),(2026,'K'),(2027,'L')");
        jdbcTemplate.update("INSERT INTO price_band(code, lower_bound, upper_bound, enabled) VALUES ('X', 0, 3000, 1)");
        jdbcTemplate.update("INSERT INTO auction_venue(code, name, enabled) VALUES ('HT', '飛騨古民具市', 1)");
        venueId = jdbcTemplate.queryForObject("SELECT id FROM auction_venue WHERE code = 'HT'", Long.class);
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

    /** 录一件（在途）→ JDBC 置在库（库存流转已由 Inventory 模块测试覆盖，此处只备货）。 */
    private long createInStockItem(MockHttpSession session, String clientReqId) throws Exception {
        String json = mockMvc.perform(post("/api/items").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody(clientReqId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
        jdbcTemplate.update("UPDATE item SET stock_status = 1, warehouse_in_date = '2026-09-20' WHERE id = ?", id);
        return id;
    }

    private String itemBody(String clientReqId) {
        return ("{\"clientReqId\":\"" + clientReqId + "\",\"venueId\":" + venueId
                + ",\"buyDate\":\"2026-09-15\",\"purchasePrice\":1000,\"warehouse\":1,"
                + "\"fee\":300,\"shippingFee\":200}").replace("\n", "");
    }

    /** CSV 构造（表头固定日文默认映射；单元格含逗号时调用方自行引号包裹）。 */
    private byte[] csv(String... rows) {
        StringBuilder sb = new StringBuilder("オークションID,商品コード,現在価格,落札価格,状態,出品日時,終了日時\r\n");
        for (String row : rows) {
            sb.append(row).append("\r\n");
        }
        return sb.toString().getBytes(MS932);
    }

    private long upload(MockHttpSession session, byte[] bytes) throws Exception {
        String json = mockMvc.perform(multipart("/api/yahoo/imports").session(session)
                        .file(new MockMultipartFile("file", "export.csv", "text/csv", bytes)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(0))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    /** 轮询批次至终态（处理段毫秒级，15s 上限防挂死）。 */
    private String awaitBatch(MockHttpSession session, long batchId) throws Exception {
        for (int i = 0; i < 150; i++) {
            String json = mockMvc.perform(get("/api/yahoo/imports/{id}", batchId).session(session))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            if (json.contains("\"status\":1") || json.contains("\"status\":2")) {
                return json;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("批次 15s 内未达终态: " + batchId);
    }

    /** CSV 标记台账的确定性幂等键（与 YahooMergeService.markClientReqId 同构）。 */
    private static String markClientReqId(String auctionId, int txnTypeId, long batchId) {
        return UUID.nameUUIDFromBytes(("yho:" + auctionId + ":" + txnTypeId + ":" + batchId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private Integer saleStatusOf(long itemId) {
        return jdbcTemplate.queryForObject("SELECT sale_status FROM item WHERE id = ?", Integer.class, itemId);
    }

    private Long soldPriceOf(long itemId) {
        return jdbcTemplate.queryForObject("SELECT sold_price FROM item WHERE id = ?", Long.class, itemId);
    }

    // ------------------------------------------------------------- 用例

    @Test
    void importFullPipeline_ms932_matchesMarksAndReports() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long item1 = createInStockItem(editor, "yho-a1");
        long item2 = createInStockItem(editor, "yho-a2");

        // item1 在售标记（全角管理号+￥千分位清洗，含逗号单元格引号包裹）；item2 未上架直报成交（0→2 边）
        long batchId = upload(editor, csv(
                "auc-101,ＨＴＫ９－Ａ１Ｘ,\"￥3,500円\",,出品中,2026/9/18 10:00,",
                "auc-102,HTK9-A2X,5000,\"12,000\",落札されました,2026/9/18 11:00,2026/9/20 21:05:33",
                "auc-103,ZZZZ-ZZ9X,2000,,出品中,2026/9/18 12:00,"));
        String report = awaitBatch(editor, batchId);

        assertThat(report).contains("\"status\":1").contains("\"encodingDetected\":\"MS932\"")
                .contains("\"rowCount\":3").contains("\"matchedCount\":2").contains("\"unmatchedCount\":1")
                .contains("\"updatedCount\":0");

        // 商品侧：LIST_UP 与 SOLD_MARK（携带 CSV 成交价）
        assertThat(saleStatusOf(item1)).isEqualTo(1);
        assertThat(saleStatusOf(item2)).isEqualTo(2);
        assertThat(soldPriceOf(item2)).isEqualTo(12000);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT client_req_id FROM stock_ledger WHERE item_id = ? AND txn_type = 9", String.class, item1))
                .isEqualTo(markClientReqId("auc-101", 9, batchId));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT yahoo_item_id FROM item WHERE id = ?", String.class, item2)).isEqualTo("auc-102");

        // listing：matched 两行挂 item_id，unmatched 行 raw 原文保留
        assertThat(jdbcTemplate.queryForObject(
                "SELECT item_id FROM yahoo_listing WHERE yahoo_auction_id = 'auc-101'", Long.class)).isEqualTo(item1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT item_id FROM yahoo_listing WHERE yahoo_auction_id = 'auc-103'", Long.class)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT raw_item_code FROM yahoo_listing WHERE yahoo_auction_id = 'auc-103'", String.class))
                .isEqualTo("ZZZZ-ZZ9X");

        // 异步线程审计：后台标记也必须落 operation_log（显式操作人，无 SecurityContext）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'YAHOO_LIST_UP' AND entity_id = ?",
                Long.class, item1)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT operator_name FROM operation_log WHERE action = 'YAHOO_SOLD_MARK' AND entity_id = ?",
                String.class, item2)).isEqualTo("eichi");

        // 报告含首见插入的 listing（updated=0）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM yahoo_listing", Long.class)).isEqualTo(3);
    }

    @Test
    void reuploadSameBytes_409() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        byte[] bytes = csv("auc-201,HTK9-A1X,1000,,出品中,2026/9/18 10:00,");
        upload(editor, bytes);
        mockMvc.perform(multipart("/api/yahoo/imports").session(editor)
                        .file(new MockMultipartFile("file", "export.csv", "text/csv", bytes)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409011));
    }

    @Test
    void staleBatch_neverRegressesSoldStatusOrPrice() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long item = createInStockItem(editor, "yho-b1");

        long soldBatch = upload(editor, csv(
                "auc-301,HTK9-A1X,5000,30000,落札されました,2026/9/10 10:00,2026/9/15 21:00"));
        awaitBatch(editor, soldBatch);
        assertThat(saleStatusOf(item)).isEqualTo(2);

        // 陈旧批次：更早事件时刻的「出品中」——状态/价格/商品侧均不得回退
        long staleBatch = upload(editor, csv(
                "auc-301,HTK9-A1X,1000,,出品中,2026/9/10 10:00,"));
        String report = awaitBatch(editor, staleBatch);
        assertThat(report).contains("\"matchedCount\":1");
        assertThat(saleStatusOf(item)).isEqualTo(2);
        assertThat(soldPriceOf(item)).isEqualTo(30000);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM yahoo_listing WHERE yahoo_auction_id = 'auc-301'", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sold_price FROM yahoo_listing WHERE yahoo_auction_id = 'auc-301'", Long.class))
                .isEqualTo(30000);
    }

    @Test
    void relist_cancelThenOnSale_walksExceptionEdge() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long item = createInStockItem(editor, "yho-c1");

        // 前置：先在售（LIST_UP 0→1）——取消边要求 saleFrom=1
        long listBatch = upload(editor, csv(
                "auc-401,HTK9-A1X,1000,,出品中,2026/9/8 10:00,"));
        awaitBatch(editor, listBatch);
        assertThat(saleStatusOf(item)).isEqualTo(1);

        long cancelBatch = upload(editor, csv(
                "auc-401,HTK9-A1X,1000,,落札されませんでした,2026/9/8 10:00,2026/9/12 21:00"));
        awaitBatch(editor, cancelBatch);
        assertThat(saleStatusOf(item)).isEqualTo(3);

        // 重新出品：listing 3→1 允许；商品侧走 CANCEL_MARK 3→1 例外边
        long relistBatch = upload(editor, csv(
                "auc-401,HTK9-A1X,1500,,出品中,2026/9/14 10:00,"));
        awaitBatch(editor, relistBatch);
        assertThat(saleStatusOf(item)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM yahoo_listing WHERE yahoo_auction_id = 'auc-401'", Integer.class)).isEqualTo(1);
        // 台账：CANCEL_MARK(1→3) 与 CANCEL_MARK(3→1) 各一行（LIST_UP 另计），幂等键各自独立
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE item_id = ? AND txn_type = 11", Long.class, item))
                .isEqualTo(2);
    }

    @Test
    void errorRowSampled_andEncodingFailure_failsBatchAsynchronously() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        createInStockItem(editor, "yho-e1"); // auc-501 的匹配目标
        // 坏状态文本行 → 错误行采样（行号/原文/原因），matched 不计数
        long batchId = upload(editor, csv(
                "auc-501,HTK9-A1X,1000,,出品中,2026/9/18 10:00,",
                "auc-502,HTK9-A2X,1000,,不明な状態,2026/9/18 10:00,"));
        String report = awaitBatch(editor, batchId);
        assertThat(report).contains("\"status\":1").contains("\"matchedCount\":1")
                .contains("\"rowCount\":2");
        String errorRows = jdbcTemplate.queryForObject(
                "SELECT error_rows FROM yahoo_import_batch WHERE id = ?", String.class, batchId);
        assertThat(errorRows).contains("auc-502").contains("2");

        // 乱码字节：上传受理（200 processing），处理段编码不可判 → 批次失败非 400
        byte[] garbage = new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, (byte) 0x81, 0x40, (byte) 0xFF};
        long badBatch = upload(editor, garbage);
        String failed = awaitBatch(editor, badBatch);
        assertThat(failed).contains("\"status\":2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_message FROM yahoo_import_batch WHERE id = ?", String.class, badBatch))
                .contains("文字コード");
    }

    @Test
    void roleMatrix_uploadRequiresEditor_readsForAll() throws Exception {
        MockHttpSession viewer = loginAs("miru");
        mockMvc.perform(multipart("/api/yahoo/imports").session(viewer)
                        .file(new MockMultipartFile("file", "export.csv", "text/csv",
                                csv("auc-601,HTK9-A1X,1000,,出品中,2026/9/18 10:00,"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/yahoo/imports").session(viewer)).andExpect(status().isOk());
        mockMvc.perform(get("/api/yahoo/reconcile").session(viewer)).andExpect(status().isOk());
        mockMvc.perform(get("/api/yahoo/pending-shipments").session(viewer)).andExpect(status().isOk());
    }

    @Test
    void pendingShipments_andReconcileViews_assembledFromItemAndListingFacts() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long soldItem = createInStockItem(editor, "yho-d1");
        long liveItem = createInStockItem(editor, "yho-d2");

        // soldItem：SOLD_MARK → 出荷待ち候选；liveItem：LIST_UP 后被线下卖出（stock=2）
        long batchId = upload(editor, csv(
                "auc-701,HTK9-A1X,5000,25000,落札されました,2026/9/1 10:00,2026/9/2 21:00",
                "auc-702,HTK9-A2X,1000,,出品中,2026/9/1 10:00,"));
        awaitBatch(editor, batchId);
        jdbcTemplate.update(
                "UPDATE item SET stock_status = 2, sale_status = 2 WHERE id = ?", liveItem);

        String pending = mockMvc.perform(get("/api/yahoo/pending-shipments").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items[0].itemCode").value("HTK9-A1X"))
                .andExpect(jsonPath("$.data.items[0].auctionId").value("auc-701"))
                .andExpect(jsonPath("$.data.items[0].soldPrice").value(25000))
                .andReturn().getResponse().getContentAsString();
        // 2026-09-02 成交、自检日 2026-09 之后 → 滞留红标
        assertThat(pending).contains("\"delayed\":true");

        String reconcile = mockMvc.perform(get("/api/yahoo/reconcile").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.soldNotShipped.length()").value(1))
                .andExpect(jsonPath("$.data.canceledNotRelisted.length()").value(0))
                .andExpect(jsonPath("$.data.withdrawNeeded.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        // 撤架视图：已出库但雅虎仍在售；listing 刚同步 → recentlySynced 降灰
        assertThat(reconcile).contains("\"itemCode\":\"HTK9-A2X\"").contains("\"recentlySynced\":true");

        // 卖出清账后出荷待ち清空
        jdbcTemplate.update("UPDATE item SET stock_status = 2 WHERE id = ?", soldItem);
        mockMvc.perform(get("/api/yahoo/pending-shipments").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(0));
    }
}
