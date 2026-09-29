package com.kcgl;

import cn.idev.excel.ExcelWriter;
import cn.idev.excel.FastExcel;
import cn.idev.excel.write.metadata.WriteSheet;
import com.kcgl.module.yahoo.YahooProperties;
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 雅虎受注 xlsx 管线集成测试（M5-②b，D-069，docs/01 7.4 全链路）：
 * - 异步全管线：xlsx multipart→批次 processing→轮询终态→计数/報告（时刻=文本序列数）
 * - 受注表=成交事实集：matched 子行全部 SOLD_MARK（未上架 0→2 直报边）携受注价，
 *   stock 不动、yahoo_item_id 留痕、listed_at/list_price 恒 NULL
 * - まとめ売り：一行多子行、単価未分割（item/listing 的 sold_price 留空手填后补）
 *   + 批次 note 補注（注文 ID 锚定）+ 幂等键含 itemId（同拍卖同批次两件独立台账）
 * - 单调不回退：陈旧重放（旧受注时刻）不回退成交态/价格；(商品,拍卖)对幂等不插行
 * - 占位行收养：自码空占位（item_id NULL）→ 商品录入后同拍卖再导入认领（id 不断链）
 * - 文件边界：非 zip 上传即 400；zip 魔数但非 xlsx→异步批次失败；表头契约→批次失败
 * - 错误行采样；sha 重复 409；角色矩阵：上传 E+，报告/对账/出荷待ち全员
 * - 出荷待ち+对账三视图（D-069 口径：视图二=手动取消标记、视图三=手动上架+盘点差异）
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

    /** 受注导出 A-U 官方 21 列布局（消费列 A/B/C/D/P 实名，其余占位被解析器忽略）。 */
    private static final List<String> HEADER = List.of(
            "OrderId", "YahooAuctionMerchantId", "OrderTime", "YahooAuctionId",
            "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12", "F13", "F14", "F15",
            "UnitPrice", "F17", "F18", "F19", "F20", "F21");

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    YahooProperties yahooProperties;

    @Test
    void propertiesBinding_inlineOverrideTakesEffect() {
        // 绑定回归锚点：record 带额外无参构造器曾静默禁用构造器绑定（与 Excel 同源缺陷，
        // 生产 yml 值恰与代码默认一致故无感）
        assertThat(yahooProperties.importsDir()).isEqualTo("target/yahoo-test-imports");
    }

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

    /** 单数据行（仅消费列有值；P=単価落位第 16 格，与 A-U 布局一致）。 */
    private static String[] orderRow(String orderId, String codes, String serialTime,
            String auctionId, String unitPrice) {
        String[] cells = new String[21];
        Arrays.fill(cells, "");
        cells[0] = orderId;
        cells[1] = codes;
        cells[2] = serialTime;
        cells[3] = auctionId;
        cells[15] = unitPrice;
        return cells;
    }

    /** 受注 xlsx 构造（全字符串单元格：时刻=文本序列数「46283.5」走确定性文本分支）。 */
    private static byte[] xlsx(List<String> header, String[]... rows) {
        List<List<String>> all = new ArrayList<>();
        all.add(header);
        for (String[] cells : rows) {
            all.add(Arrays.asList(cells));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = FastExcel.write(out).build()) {
            WriteSheet sheet = FastExcel.writerSheet(0, "受注").build();
            writer.write(all, sheet);
        }
        return out.toByteArray();
    }

    private static byte[] xlsx(String[]... rows) {
        return xlsx(HEADER, rows);
    }

    private long upload(MockHttpSession session, byte[] bytes) throws Exception {
        String json = mockMvc.perform(multipart("/api/yahoo/imports").session(session)
                        .file(new MockMultipartFile("file", "orders.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes)))
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

    /** 受注 SOLD_MARK 台账的确定性幂等键（与 YahooMergeService 同构：auction+item+batch）。 */
    private static String markClientReqId(String auctionId, long itemId, long batchId) {
        return UUID.nameUUIDFromBytes(("yho:" + auctionId + ":" + itemId + ":10:" + batchId)
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
    void importFullPipeline_ordersMarkSold_andReported() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long item1 = createInStockItem(editor, "yho-a1"); // HT9-A1X
        long item2 = createInStockItem(editor, "yho-a2"); // HT9-A2X

        // 46283.5=2026-09-18 12:00；两件在库未上架 → SOLD_MARK 0→2 直报边；旧码行未匹配
        long batchId = upload(editor, xlsx(
                orderRow("10004866", "HT9-A1X", "46283.5", "b1243949032", "3500"),
                orderRow("10004867", "HT9-A2X", "46283.5", "b1243949033", "12000"),
                orderRow("10004868", "ZZZZ-ZZ9X", "46283.5", "b1243949034", "2000")));
        String report = awaitBatch(editor, batchId);

        assertThat(report).contains("\"status\":1")
                .contains("\"rowCount\":3").contains("\"matchedCount\":2").contains("\"unmatchedCount\":1")
                .contains("\"updatedCount\":0")
                .doesNotContain("encodingDetected");

        // 商品侧：受注=成交事实集，matched 全部 SOLD_MARK 携受注价（stock 不动）
        assertThat(saleStatusOf(item1)).isEqualTo(2);
        assertThat(saleStatusOf(item2)).isEqualTo(2);
        assertThat(soldPriceOf(item1)).isEqualTo(3500);
        assertThat(soldPriceOf(item2)).isEqualTo(12000);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_status FROM item WHERE id = ?", Integer.class, item1)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT client_req_id FROM stock_ledger WHERE item_id = ? AND txn_type = 10",
                String.class, item1)).isEqualTo(markClientReqId("b1243949032", item1, batchId));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT yahoo_item_id FROM item WHERE id = ?", String.class, item2))
                .isEqualTo("b1243949033");

        // listing：order_id 留痕、closed_at=受注时刻、listed_at/list_price 恒 NULL；
        // unmatched 行 raw 原文保留
        assertThat(jdbcTemplate.queryForObject(
                "SELECT item_id FROM yahoo_listing WHERE yahoo_auction_id = 'b1243949032'", Long.class))
                .isEqualTo(item1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT order_id FROM yahoo_listing WHERE yahoo_auction_id = 'b1243949032'", String.class))
                .isEqualTo("10004866");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(closed_at, '%Y-%m-%d %H:%i') FROM yahoo_listing "
                        + "WHERE yahoo_auction_id = 'b1243949032'", String.class))
                .isEqualTo("2026-09-18 12:00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT item_id FROM yahoo_listing WHERE yahoo_auction_id = 'b1243949034'", Long.class))
                .isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT raw_item_code FROM yahoo_listing WHERE yahoo_auction_id = 'b1243949034'",
                String.class)).isEqualTo("ZZZZ-ZZ9X");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM yahoo_listing WHERE listed_at IS NULL AND list_price IS NULL",
                Long.class)).isEqualTo(3);

        // 异步线程审计：后台标记也必须落 operation_log（显式操作人，无 SecurityContext）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT operator_name FROM operation_log WHERE action = 'YAHOO_SOLD_MARK' AND entity_id = ?",
                String.class, item2)).isEqualTo("eichi");
    }

    @Test
    void multiCodeOrder_bothMarked_priceUndivided_noteAttached() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long item1 = createInStockItem(editor, "yho-m1"); // HT9-A1X
        long item2 = createInStockItem(editor, "yho-m2"); // HT9-A2X

        long batchId = upload(editor, xlsx(
                orderRow("10004900", "HT9-A1X\nHT9-A2X", "46283.5", "x1243954505", "12595")));
        String report = awaitBatch(editor, batchId);

        // rowCount=物理行；matched 按子行（一行拆两子行）
        assertThat(report).contains("\"rowCount\":1").contains("\"matchedCount\":2")
                .contains("\"unmatchedCount\":0").contains("\"updatedCount\":0");

        // 批次 note 補注：注文 ID 锚定 + 双码「・」连接（D-069 4）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT note FROM yahoo_import_batch WHERE id = ?", String.class, batchId))
                .contains("注文10004900").contains("HT9-A1X・HT9-A2X").contains("単価が未分割");

        // 商品侧：双件 SOLD_MARK；単価未分割 → sold_price 不写（手填后补）
        assertThat(saleStatusOf(item1)).isEqualTo(2);
        assertThat(saleStatusOf(item2)).isEqualTo(2);
        assertThat(soldPriceOf(item1)).isNull();
        assertThat(soldPriceOf(item2)).isNull();

        // listing：同拍卖两行各挂 item、sold_price NULL、orderId 留痕
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM yahoo_listing WHERE yahoo_auction_id = 'x1243954505' "
                        + "AND sold_price IS NULL AND order_id = '10004900'", Long.class)).isEqualTo(2);

        // 幂等键含 itemId：同拍卖同批次两件各自独立台账与审计
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE txn_type = 10", Long.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'YAHOO_SOLD_MARK'", Long.class))
                .isEqualTo(2);
    }

    @Test
    void reuploadSameBytes_409() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        byte[] bytes = xlsx(orderRow("10006001", "HT9-A1X", "46283.5", "auc-601", "1000"));
        upload(editor, bytes);
        mockMvc.perform(multipart("/api/yahoo/imports").session(editor)
                        .file(new MockMultipartFile("file", "orders.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409011));
    }

    @Test
    void staleReplay_neverRegresses_pairIdempotent() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long item = createInStockItem(editor, "yho-b1"); // HT9-A1X

        long soldBatch = upload(editor, xlsx(
                orderRow("10005001", "HT9-A1X", "46280.875", "auc-301", "30000"))); // 09-15 21:00
        awaitBatch(editor, soldBatch);
        assertThat(saleStatusOf(item)).isEqualTo(2);
        assertThat(soldPriceOf(item)).isEqualTo(30000);

        // 陈旧重放：同拍卖同商品、更早受注时刻（09-10）、更低价 → 不得回退
        long staleBatch = upload(editor, xlsx(
                orderRow("10005002", "HT9-A1X", "46275.5", "auc-301", "1000")));
        String report = awaitBatch(editor, staleBatch);
        assertThat(report).contains("\"matchedCount\":1").contains("\"updatedCount\":1");
        assertThat(saleStatusOf(item)).isEqualTo(2);
        assertThat(soldPriceOf(item)).isEqualTo(30000);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM yahoo_listing WHERE yahoo_auction_id = 'auc-301'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sold_price FROM yahoo_listing WHERE yahoo_auction_id = 'auc-301'",
                Long.class)).isEqualTo(30000);
        // (商品,拍卖)对幂等：不插新行；成交态不重复标记
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM yahoo_listing WHERE yahoo_auction_id = 'auc-301'",
                Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE item_id = ? AND txn_type = 10",
                Long.class, item)).isEqualTo(1);
    }

    @Test
    void placeholderAdoption_claimedByLaterImport_keepsRowIdentity() throws Exception {
        MockHttpSession editor = loginAs("eichi");

        // 首导入：自码空 → 占位行（订单事实保留，item_id NULL）
        long firstBatch = upload(editor, xlsx(
                orderRow("10005101", "", "46283.5", "auc-501", "7777")));
        assertThat(awaitBatch(editor, firstBatch))
                .contains("\"rowCount\":1").contains("\"unmatchedCount\":1");
        long placeholderId = jdbcTemplate.queryForObject(
                "SELECT id FROM yahoo_listing WHERE yahoo_auction_id = 'auc-501'", Long.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT item_id FROM yahoo_listing WHERE id = ?", Long.class, placeholderId)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sold_price FROM yahoo_listing WHERE id = ?", Long.class, placeholderId))
                .isEqualTo(7777);

        // 商品录入后同拍卖再导入（受注时刻更新、自码命中）→ 占位行被认领，id 不断链
        long item = createInStockItem(editor, "yho-e1"); // HT9-A1X
        long secondBatch = upload(editor, xlsx(
                orderRow("10005102", "HT9-A1X", "46284.5", "auc-501", "8888"))); // 09-19 12:00
        assertThat(awaitBatch(editor, secondBatch))
                .contains("\"matchedCount\":1").contains("\"updatedCount\":1");
        assertThat(saleStatusOf(item)).isEqualTo(2);
        assertThat(soldPriceOf(item)).isEqualTo(8888);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM yahoo_listing WHERE yahoo_auction_id = 'auc-501'",
                Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT id FROM yahoo_listing WHERE yahoo_auction_id = 'auc-501'", Long.class))
                .isEqualTo(placeholderId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT item_id FROM yahoo_listing WHERE yahoo_auction_id = 'auc-501'", Long.class))
                .isEqualTo(item);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT raw_item_code FROM yahoo_listing WHERE yahoo_auction_id = 'auc-501'",
                String.class)).isEqualTo("HT9-A1X");
    }

    @Test
    void badFileBoundary_nonZip400_zipGarbageAsyncFail_headerContractBatchFail() throws Exception {
        MockHttpSession editor = loginAs("eichi");

        // 非 zip（旧 CSV 字节）→ 上传即 400：受注导出=xlsx，引导转存
        mockMvc.perform(multipart("/api/yahoo/imports").session(editor)
                        .file(new MockMultipartFile("file", "export.csv", "text/csv",
                                "オークションID,商品コード\r\n".getBytes(MS932))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400012));

        // zip 魔数但非 xlsx → 受理 processing，处理段解析失败 → 批次异步失败
        byte[] fakeZip = new byte[]{'P', 'K', 3, 4, 1, 2, 3, 4, 5, 6};
        long badBatch = upload(editor, fakeZip);
        String failed = awaitBatch(editor, badBatch);
        assertThat(failed).contains("\"status\":2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_message FROM yahoo_import_batch WHERE id = ?", String.class, badBatch))
                .contains("インポート処理中にエラー");

        // 表头契约（误传出品状態表：B/P 列错位）→ 批次失败带列位指引
        List<String> badHeader = new ArrayList<>(HEADER);
        badHeader.set(1, "商品コード");
        badHeader.set(15, "合計金額");
        long headerBatch = upload(editor, xlsx(badHeader,
                orderRow("10005201", "HT9-A1X", "46283.5", "auc-521", "1000")));
        assertThat(awaitBatch(editor, headerBatch)).contains("\"status\":2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_message FROM yahoo_import_batch WHERE id = ?", String.class, headerBatch))
                .contains("B列は「YahooAuctionMerchantId」").contains("P列は「UnitPrice」");
    }

    @Test
    void headerMismatchLongCellMessage_truncatedToColumnWidth_batchStillFails() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        // A1 超长文本（xlsx 单格 ≤32767 字）→ 不符消息内嵌原文；不截断则
        // error_message VARCHAR(500) 在 STRICT 模式下 UPDATE 抛异常逃逸 catch →
        // 批次永久 processing（安全评审 MEDIUM-3，普通用户误传带长标题行的报表
        // 也可触发——非仅蓄意构造）
        List<String> badHeader = new ArrayList<>(HEADER);
        badHeader.set(0, "長い見出し".repeat(200));
        long batchId = upload(editor, xlsx(badHeader,
                orderRow("10005201", "HT9-A1X", "46283.5", "auc-521", "1000")));
        assertThat(awaitBatch(editor, batchId)).contains("\"status\":2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_message FROM yahoo_import_batch WHERE id = ?", String.class, batchId))
                .contains("A列は「OrderId」")
                .hasSizeLessThanOrEqualTo(500);
    }

    @Test
    void errorSampleCappedAtThousand_largeBadFileCompletes() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        // 1500 行坏价：采样只留前 1000（读取期内存随行数有界——不截断 reason 且
        // 全量累积时，100k 行 × 32KB 格可冲到 GB 级堆，安全评审 MEDIUM-1）；
        // 计数仍全量、批次照常完成
        String[][] rows = new String[1500][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = orderRow(String.valueOf(90000000 + i), "HT9-A1X", "46283.5",
                    "auc-cap-" + i, "三百円");
        }
        long batchId = upload(editor, xlsx(rows));
        assertThat(awaitBatch(editor, batchId)).contains("\"status\":1").contains("\"rowCount\":1500");
        String errorRows = jdbcTemplate.queryForObject(
                "SELECT error_rows FROM yahoo_import_batch WHERE id = ?", String.class, batchId);
        assertThat(errorRows.split("\"line\":", -1).length - 1).isEqualTo(1000);
    }

    @Test
    void multiCodeOrder_bothUnmatched_eachCodeKeepsOwnPlaceholderRow() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        // D-069 5 不变量：同拍卖 2 件=2 行。两旧码均未命中 → 两个占位行各自留痕，
        // 不塌缩成一格（兄弟子行不得复用同批占位行——代码评审 MEDIUM-1 场景 A）
        long batchId = upload(editor, xlsx(
                orderRow("10006101", "OLD1-ZZ1\nOLD2-ZZ2", "46283.5", "auc-m1", "5000")));
        assertThat(awaitBatch(editor, batchId))
                .contains("\"status\":1").contains("\"rowCount\":1")
                .contains("\"matchedCount\":0").contains("\"unmatchedCount\":2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM yahoo_listing WHERE yahoo_auction_id = ?",
                Integer.class, "auc-m1")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "SELECT raw_item_code FROM yahoo_listing WHERE yahoo_auction_id = ?"
                        + " ORDER BY raw_item_code",
                String.class, "auc-m1")).containsExactly("OLD1-ZZ1", "OLD2-ZZ2");
    }

    @Test
    void multiCodeOrder_mixedMatch_itemRowPlusPlaceholderRowBothKept() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long item = createInStockItem(editor, "yho-a1"); // HT9-A1X
        // 混合形态：未命中码+命中码 → 占位行与 (item,auction) 行各一行、命中件
        // SOLD_MARK（代码评审 MEDIUM-1 场景 B：命中子行不得收养兄弟刚插的占位行）
        long batchId = upload(editor, xlsx(
                orderRow("10006102", "OLD9-ZZ9\nHT9-A1X", "46283.5", "auc-m2", "5000")));
        assertThat(awaitBatch(editor, batchId))
                .contains("\"status\":1").contains("\"rowCount\":1")
                .contains("\"matchedCount\":1").contains("\"unmatchedCount\":1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM yahoo_listing WHERE yahoo_auction_id = ?",
                Integer.class, "auc-m2")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM yahoo_listing WHERE yahoo_auction_id = ? AND item_id IS NULL",
                Integer.class, "auc-m2")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM yahoo_listing WHERE yahoo_auction_id = ? AND item_id = ?",
                Integer.class, "auc-m2", item)).isEqualTo(1);
        assertThat(saleStatusOf(item)).isEqualTo(2);
    }

    @Test
    void inFileRowOrder_newerEventFirst_olderRowDoesNotRegress() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        createInStockItem(editor, "yho-a1"); // HT9-A1X
        // 确定性矩阵「乱序」形态：同文件同 (商品,拍卖) 对、新时刻行在前旧在后
        // → recency 保新值（行序不敏感锚定）
        long batchId = upload(editor, xlsx(
                orderRow("10006201", "HT9-A1X", "46284.0", "auc-ord", "7000"),
                orderRow("10006202", "HT9-A1X", "46283.0", "auc-ord", "6000")));
        awaitBatch(editor, batchId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sold_price FROM yahoo_listing WHERE yahoo_auction_id = ?",
                Long.class, "auc-ord")).isEqualTo(7000L);
    }

    @Test
    void emptyFirstSheet_batchFails_withGuidance() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        // 零行首表：invoke 从未回调，表头契约若无 doRead 后兜底会被整体绕过
        //（静默 DONE 0 行——代码评审 MEDIUM-2）
        long batchId = upload(editor, emptySheetXlsx());
        assertThat(awaitBatch(editor, batchId)).contains("\"status\":2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_message FROM yahoo_import_batch WHERE id = ?", String.class, batchId))
                .contains("最初のワークシートが空");
    }

    /** 首表零行的合法 xlsx（write(空集合) 不产生任何行）。 */
    private static byte[] emptySheetXlsx() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = FastExcel.write(out).build()) {
            writer.write(List.of(), FastExcel.writerSheet(0, "受注").build());
        }
        return out.toByteArray();
    }

    @Test
    void errorRowSampled_badPriceKeptOthersFlow() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        createInStockItem(editor, "yho-e2"); // HT9-A1X 的匹配目标

        long batchId = upload(editor, xlsx(
                orderRow("10005201", "HT9-A1X", "46283.5", "auc-521", "1000"),
                orderRow("10005202", "HT9-A2X", "46283.5", "auc-522", "三百円")));
        String report = awaitBatch(editor, batchId);
        assertThat(report).contains("\"status\":1").contains("\"rowCount\":2")
                .contains("\"matchedCount\":1").contains("\"unmatchedCount\":0");
        // 坏行不连坐：错误行采样带行号/原文/原因，其余行照常合并
        String errorRows = jdbcTemplate.queryForObject(
                "SELECT error_rows FROM yahoo_import_batch WHERE id = ?", String.class, batchId);
        assertThat(errorRows).contains("auc-522").contains("落札価格");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM yahoo_listing WHERE yahoo_auction_id = 'auc-522'",
                Long.class)).isZero();
    }

    @Test
    void roleMatrix_uploadRequiresEditor_readsForAll() throws Exception {
        MockHttpSession viewer = loginAs("miru");
        mockMvc.perform(multipart("/api/yahoo/imports").session(viewer)
                        .file(new MockMultipartFile("file", "orders.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                xlsx(orderRow("10006001", "HT9-A1X", "46283.5", "auc-601", "1000")))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/yahoo/imports").session(viewer)).andExpect(status().isOk());
        mockMvc.perform(get("/api/yahoo/reconcile").session(viewer)).andExpect(status().isOk());
        mockMvc.perform(get("/api/yahoo/pending-shipments").session(viewer)).andExpect(status().isOk());
    }

    @Test
    void pendingShipments_andReconcileViews_assembledFromItemAndManualMarks() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long soldItem = createInStockItem(editor, "yho-d1"); // HT9-A1X
        long liveItem = createInStockItem(editor, "yho-d2"); // HT9-A2X
        long canceledItem = createInStockItem(editor, "yho-d3"); // HT9-A3X

        // soldItem：受注成交 → 出荷待ち/视图一（2026-09-02 21:00 → 滞留红标）
        long batchId = upload(editor, xlsx(
                orderRow("10007001", "HT9-A1X", "46267.875", "auc-701", "25000")));
        awaitBatch(editor, batchId);

        // liveItem：手动上架后在售；盘点差异出库（stock=2, sale=1）→ 撤架视图三
        mockMvc.perform(post("/api/inventory/mark-listed").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + liveItem + ",\"clientReqId\":\"yho-view-up\"}"))
                .andExpect(status().isOk());
        jdbcTemplate.update("UPDATE item SET stock_status = 2 WHERE id = ?", liveItem);

        // canceledItem：手动上架→流拍取消 → 视图二（流拍未重上）
        mockMvc.perform(post("/api/inventory/mark-listed").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + canceledItem + ",\"clientReqId\":\"yho-view-up2\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/inventory/mark-canceled").session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + canceledItem + ",\"clientReqId\":\"yho-view-cancel\"}"))
                .andExpect(status().isOk());

        String pending = mockMvc.perform(get("/api/yahoo/pending-shipments").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items[0].itemCode").value("HT9-A1X"))
                .andExpect(jsonPath("$.data.items[0].orderId").value("10007001"))
                .andExpect(jsonPath("$.data.items[0].auctionId").value("auc-701"))
                .andExpect(jsonPath("$.data.items[0].soldPrice").value(25000))
                .andReturn().getResponse().getContentAsString();
        assertThat(pending).contains("\"delayed\":true");

        String reconcile = mockMvc.perform(get("/api/yahoo/reconcile").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.soldNotShipped.length()").value(1))
                .andExpect(jsonPath("$.data.canceledNotRelisted.length()").value(1))
                .andExpect(jsonPath("$.data.withdrawNeeded.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        // 视图二=手动取消标记驱动；视图三=手动上架+盘点差异，lastSyncedAt 兜底最近导入
        // 完成时刻（受注导入刚完成）→ recentlySynced 降灰
        assertThat(reconcile).contains("\"itemCode\":\"HT9-A2X\"")
                .contains("\"itemCode\":\"HT9-A3X\"").contains("\"recentlySynced\":true");

        // 卖出清账后出荷待ち清空
        jdbcTemplate.update("UPDATE item SET stock_status = 2 WHERE id = ?", soldItem);
        mockMvc.perform(get("/api/yahoo/pending-shipments").session(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(0));
    }
}
