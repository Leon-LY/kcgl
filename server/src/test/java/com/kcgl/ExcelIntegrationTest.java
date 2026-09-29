package com.kcgl;

import cn.idev.excel.ExcelWriter;
import cn.idev.excel.FastExcel;
import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.event.AnalysisEventListener;
import cn.idev.excel.write.metadata.WriteSheet;
import com.kcgl.module.auth.KcglUserDetails;
import com.kcgl.module.excel.ExcelImportService;
import com.kcgl.module.excel.ExcelProperties;
import com.kcgl.module.excel.ExcelRowParser;
import com.kcgl.module.itemcode.CreateItemCommand;
import com.kcgl.module.itemcode.ItemCodeService;
import com.kcgl.module.user.SysUserEntity;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Excel 管线集成测试（M4-⑤，D-058 全链路）：
 * - 模板下载=表头契约+記入方法双 Sheet，模板原样再导入=空批次成功（往返自证）
 * - 双模式导入：旧号推进计数器（跳变说明 A0→A5）+ 生成模式接续（A6Y）
 * - 旧号低于计数器=历史件不回拨；sha 重复 409（成功后/失败后同占位）
 * - 表头不符→批次失败零落库；坏行采样不连坐好行；行数上限→批次失败（先入行已提交）
 * - 空/非 zip 文件→上传即 400；角色矩阵（模板/导入 E+，报告/导出全员）
 * - 导出：25 列+筛选+単票优先+作废排除+状态日文文案
 * - 并发不变量：导入与生成器同桶对撞→码唯一+cur_seq==MAX(seq_no)+无 500
 * - 僵尸自愈：processing 超 30 分钟标记失败
 *
 * <p>类级 max-rows=3：常规用例 ≤3 行、行数上限用例 4 行、并发用例 3 行旧号——
 * 覆写经 2026-09-28 绑定修复（record 额外无参构造器会静默禁用构造器绑定）后真正生效。
 */
@SpringBootTest(properties = {
        "kcgl.security.allowed-origins=https://kcgl.example.com",
        "kcgl.excel.imports-dir=target/excel-test-imports",
        "kcgl.excel.max-rows=3"})
@AutoConfigureMockMvc
@Testcontainers
class ExcelIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("kcgl")
            .withCommand("mysqld",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+09:00");

    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    static final String PASSWORD = "Exl-1234-x";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    ExcelImportService excelImportService;
    @Autowired
    ItemCodeService itemCodeService;
    @Autowired
    ExcelProperties excelProperties;

    long venueId;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void resetFixtures() {
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM stock_ledger");
        jdbcTemplate.update("DELETE FROM excel_import_batch");
        jdbcTemplate.update("DELETE FROM item");
        jdbcTemplate.update("DELETE FROM seq_item_code");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username IN ('boss','eichi','miru')");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('boss', ?, '管理者', 1, 1, 0), ('eichi', ?, '編集者', 2, 1, 0), ('miru', ?, '閲覧者', 3, 1, 0)
                """, ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD), ENCODER.encode(PASSWORD));
        jdbcTemplate.update("DELETE FROM auction_venue");
        jdbcTemplate.update("DELETE FROM price_band");
        jdbcTemplate.update(
                "INSERT INTO price_band(code, lower_bound, upper_bound, enabled) VALUES ('X', 0, 3000, 1), ('Y', 3000, 1000000, 1)");
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

    /** 19 列数据行（默认空串，覆盖指定槽位）。 */
    private static List<Object> dataRow(String itemCode, String venueCode, String buyDate,
            Object price, String warehouse) {
        List<Object> cells = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            cells.add("");
        }
        cells.set(0, itemCode);
        cells.set(1, venueCode);
        cells.set(2, buyDate);
        cells.set(3, price);
        cells.set(7, warehouse);
        return cells;
    }

    /** 构造 xlsx：行 1=19 列契约表头（可覆写以制造不符），行 2+=数据。 */
    private byte[] xlsx(List<String> headerOverrideFirstColumn, List<List<Object>> dataRows)
            throws IOException {
        List<List<String>> head = new ArrayList<>();
        List<String> names = ExcelProperties.defaults().columns().headerOrder();
        for (int i = 0; i < names.size(); i++) {
            head.add(List.of(i == 0 && headerOverrideFirstColumn != null
                    ? headerOverrideFirstColumn.get(0) : names.get(i)));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = FastExcel.write(out).build()) {
            WriteSheet sheet = FastExcel.writerSheet(0, "商品").head(head).build();
            writer.write(dataRows, sheet);
        }
        return out.toByteArray();
    }

    private long upload(MockHttpSession session, byte[] bytes, String filename) throws Exception {
        String json = mockMvc.perform(multipart("/api/excel/items/import").session(session)
                        .file(new MockMultipartFile("file", filename,
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(0))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    /** 轮询批次至终态（处理段毫秒级，15s 上限防挂死）。 */
    private String awaitBatch(MockHttpSession session, long batchId) throws Exception {
        for (int i = 0; i < 150; i++) {
            String json = mockMvc.perform(get("/api/excel/items/imports/{id}", batchId).session(session))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            if (json.contains("\"status\":1") || json.contains("\"status\":2")) {
                return json;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("批次 15s 内未达终态: " + batchId);
    }

    /** 读回 xlsx 全部行（含表头行；空单元格=null 占位到 30 列）。 */
    private static List<List<Object>> readAll(byte[] bytes, int sheetIndex) throws IOException {
        List<List<Object>> rows = new ArrayList<>();
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            FastExcel.read(in, new AnalysisEventListener<Map<Integer, Object>>() {
                @Override
                public void invoke(Map<Integer, Object> row, AnalysisContext context) {
                    List<Object> cells = new ArrayList<>();
                    for (int i = 0; i < 30; i++) {
                        cells.add(row.get(i));
                    }
                    rows.add(cells);
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                }
            }).sheet(sheetIndex).headRowNumber(0).doRead();
        }
        return rows;
    }

    private long itemCodeCount(String code) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM item WHERE item_code = ?",
                Long.class, code);
    }

    private String counterOf() {
        return jdbcTemplate.queryForObject(
                "SELECT CONCAT(cur_prefix, cur_seq) FROM seq_item_code WHERE venue_id = ? AND month = 9",
                String.class, venueId);
    }

    private String itemBody(String clientReqId) {
        return ("{\"clientReqId\":\"" + clientReqId + "\",\"venueId\":" + venueId
                + ",\"buyDate\":\"2026-09-15\",\"purchasePrice\":1000,\"warehouse\":1,"
                + "\"fee\":300,\"shippingFee\":200}").replace("\n", "");
    }

    /**
     * StreamingResponseBody 在 MockMvc 中必须 asyncDispatch 二段取回——直接 perform
     * 只有异步启动（响应体 0 字节），流写入发生在 dispatch 之前的执行器线程上。
     */
    private byte[] download(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        return result.getResponse().getContentAsByteArray();
    }

    private byte[] export(MockHttpSession session, String from, String to, String code)
            throws Exception {
        MockHttpServletRequestBuilder request = get("/api/excel/items/export")
                .param("createdFrom", from).param("createdTo", to).session(session);
        if (code != null) {
            request.param("code", code);
        }
        return download(request);
    }

    /** 直接调服务层（镜像 ItemCodeIntegrationTest）：审计读 SecurityContext，需显式装填。 */
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

    // ------------------------------------------------------------- 用例

    @Test
    void templateDownload_matchesHeaderContract_andReimportsAsEmptyBatch() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        byte[] template = download(get("/api/excel/items/template").session(editor));
        // xlsx=zip 容器；商品 Sheet 表头=19 列契约；記入方法 Sheet 非空
        assertThat(template.length).isGreaterThan(4);
        assertThat(new String(template, 0, 2, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("PK");
        List<List<Object>> sheet1 = readAll(template, 0);
        assertThat(sheet1).isNotEmpty();
        List<String> header = sheet1.get(0).stream().limit(19)
                .map(ExcelRowParser::cellText).toList();
        assertThat(header).containsExactlyElementsOf(ExcelProperties.defaults().columns().headerOrder());
        assertThat(readAll(template, 1)).isNotEmpty();

        // 模板原样导入=空批次成功（表头通过、0 数据行——往返自证）
        long batchId = upload(editor, template, "template.xlsx");
        String report = awaitBatch(editor, batchId);
        assertThat(report).contains("\"status\":1").contains("\"rowCount\":0")
                .contains("\"errorCount\":0");
    }

    @Test
    void importDualMode_oldCodeAdvancesCounter_thenGeneratedContinues() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        // 行 1：旧号导入（金额为数值单元格——真实文件里 Excel 把数字存 double 的经典形态）
        long batchId = upload(editor, xlsx(null, List.of(
                dataRow("HT9-A5X", "HT", "2026-09-01", 12000, "名古屋"),
                dataRow("", "HT", "2026/9/2", "8000", "福岡"))), "dual.xlsx");
        String report = awaitBatch(editor, batchId);
        assertThat(report).contains("\"status\":1").contains("\"rowCount\":2")
                .contains("\"generatedCount\":1").contains("\"importedCount\":1")
                .contains("\"errorCount\":0");

        // 旧号 A5X 落库；生成件接续计数器（8000→Y 档→HT9-A6Y）
        assertThat(itemCodeCount("HT9-A5X")).isEqualTo(1);
        assertThat(itemCodeCount("HT9-A6Y")).isEqualTo(1);
        assertThat(counterOf()).isEqualTo("A6");
        // 跳变说明：桶从 A0 被旧号推到 A5
        assertThat(jdbcTemplate.queryForObject(
                "SELECT note FROM excel_import_batch WHERE id = ?", String.class, batchId))
                .contains("A0→A5");
        // 后台线程审计（无 SecurityContext 的 6 参路径）仍落操作人
        assertThat(jdbcTemplate.queryForObject(
                "SELECT operator_name FROM operation_log WHERE action = 'ITEM_IMPORT'", String.class))
                .isEqualTo("eichi");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE action = 'ITEM_CREATE'", Long.class))
                .isEqualTo(1);
        // 新导入件默认在途/未出品
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM item WHERE stock_status = 0 AND sale_status = 0", Long.class))
                .isEqualTo(2);
    }

    @Test
    void importOldCodeBehindCounter_historicalNoJump() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        // A9 先进（跳变 A0→A9），A5 后进（≤cur 历史件，不回拨不产生第二条跳变）
        long batchId = upload(editor, xlsx(null, List.of(
                dataRow("HT9-A9X", "HT", "2026-09-01", 12000, "1"),
                dataRow("HT9-A5Z", "HT", "2026-09-01", 8000, "1"))), "history.xlsx");
        String report = awaitBatch(editor, batchId);
        assertThat(report).contains("\"status\":1").contains("\"importedCount\":2")
                .contains("\"errorCount\":0");
        assertThat(counterOf()).isEqualTo("A9");
        String note = jdbcTemplate.queryForObject(
                "SELECT note FROM excel_import_batch WHERE id = ?", String.class, batchId);
        assertThat(note).contains("A0→A9").doesNotContain("A5→");
        // 同前缀同流水、不同价格码字母=两件并存（档位字母变体合法性）
        assertThat(itemCodeCount("HT9-A9X")).isEqualTo(1);
        assertThat(itemCodeCount("HT9-A5Z")).isEqualTo(1);
    }

    @Test
    void reuploadSameBytes_409_afterDone_andAfterFailure() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        byte[] good = xlsx(null, List.of(dataRow("", "HT", "2026-09-01", "1000", "1")));
        long done = upload(editor, good, "good.xlsx");
        awaitBatch(editor, done);
        mockMvc.perform(multipart("/api/excel/items/import").session(editor)
                        .file(new MockMultipartFile("file", "good.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", good)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409012));

        // 失败批次同 sha 占位：同一坏文件重传也 409（改好后的文件字节不同=新 sha 放行）
        byte[] bad = xlsx(List.of("商品番号"), List.of(dataRow("", "HT", "2026-09-01", "1000", "1")));
        long failed = upload(editor, bad, "bad.xlsx");
        awaitBatch(editor, failed);
        mockMvc.perform(multipart("/api/excel/items/import").session(editor)
                        .file(new MockMultipartFile("file", "bad.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bad)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409012));
    }

    @Test
    void headerMismatch_failsBatch_nothingImported() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        byte[] bytes = xlsx(List.of("商品番号"), List.of(
                dataRow("", "HT", "2026-09-01", "1000", "1"),
                dataRow("HT9-A5X", "HT", "2026-09-01", "1000", "1")));
        long batchId = upload(editor, bytes, "wrong-header.xlsx");
        String report = awaitBatch(editor, batchId);
        assertThat(report).contains("\"status\":2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_message FROM excel_import_batch WHERE id = ?", String.class, batchId))
                .contains("1列目").contains("管理番号").contains("商品番号");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM item", Long.class)).isZero();
    }

    @Test
    void rowLimitExceeded_failsBatch_committedRowsRetained() throws Exception {
        // 绑定回归锚点：record 带额外无参构造器曾静默禁用构造器绑定（恒为默认 20000）
        assertThat(excelProperties.maxRows())
                .as("类级 kcgl.excel.max-rows=3 应绑定生效")
                .isEqualTo(3);
        MockHttpSession editor = loginAs("eichi");
        // 类级 max-rows=3：第 4 行触发上限 → 批次失败；先入 3 行已各自提交（一行一事务）
        long batchId = upload(editor, xlsx(null, List.of(
                dataRow("", "HT", "2026-09-01", "1000", "1"),
                dataRow("", "HT", "2026-09-02", "1000", "1"),
                dataRow("", "HT", "2026-09-03", "1000", "1"),
                dataRow("", "HT", "2026-09-04", "1000", "1"))), "over-limit.xlsx");
        String report = awaitBatch(editor, batchId);
        assertThat(report).contains("\"status\":2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_message FROM excel_import_batch WHERE id = ?", String.class, batchId))
                .isNotBlank();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM item", Long.class)).isEqualTo(3);
    }

    @Test
    void badRowSampled_goodRowsContinue() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        long batchId = upload(editor, xlsx(null, List.of(
                dataRow("HT9-A5X", "HT", "2026-09-01", "1000", "1"),
                dataRow("", "HT", "2026-09-01", "1000", "3"),   // 倉庫 3=行错误
                dataRow("", "HT", "2026-09-02", "1000", "1"))), "mixed.xlsx");
        String report = awaitBatch(editor, batchId);
        assertThat(report).contains("\"status\":1").contains("\"rowCount\":3")
                .contains("\"importedCount\":1").contains("\"generatedCount\":1")
                .contains("\"errorCount\":1");
        // 错误行采样：line=3（表头行 1+数据第 2 行）、原因含列名。
        // error_rows 是 MySQL JSON 列——SELECT 回读是 MySQL 规范文本（"line": 3 冒号后带空格），
        // 断言用正则容忍空白，不能按 Jackson 紧凑形态写死
        String errorRows = jdbcTemplate.queryForObject(
                "SELECT error_rows FROM excel_import_batch WHERE id = ?", String.class, batchId);
        assertThat(errorRows).containsPattern("\"line\"\\s*:\\s*3").contains("倉庫");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM item", Long.class)).isEqualTo(2);
    }

    @Test
    void emptyOrNonZipFile_rejectedAtUploadAs400() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        mockMvc.perform(multipart("/api/excel/items/import").session(editor)
                        .file(new MockMultipartFile("file", "empty.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400014));
        mockMvc.perform(multipart("/api/excel/items/import").session(editor)
                        .file(new MockMultipartFile("file", "fake.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "管理番号,会場コード".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400014));
    }

    @Test
    void roleMatrix_templateAndImportRequireEditor_readsForAll() throws Exception {
        // 详情端点用真实批次覆盖（AUTO_INCREMENT 跨用例不归零，按实际 id 断言）
        MockHttpSession editor = loginAs("eichi");
        long batchId = upload(editor, xlsx(null, List.of(
                dataRow("", "HT", "2026-09-01", "1000", "1"))), "rm.xlsx");
        awaitBatch(editor, batchId);

        MockHttpSession viewer = loginAs("miru");
        byte[] bytes = xlsx(null, List.of());
        mockMvc.perform(multipart("/api/excel/items/import").session(viewer)
                        .file(new MockMultipartFile("file", "v.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/excel/items/template").session(viewer))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/excel/items/imports").session(viewer)).andExpect(status().isOk());
        mockMvc.perform(get("/api/excel/items/imports/{id}", batchId).session(viewer))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/excel/items/export")
                        .param("createdFrom", "2026-01-01").param("createdTo", "2026-12-31")
                        .session(viewer))
                .andExpect(status().isOk());
    }

    @Test
    void export_streamingReport_filtersAndJapaneseStatusText() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        String json1 = mockMvc.perform(post("/api/items").session(editor)
                        .contentType(MediaType.APPLICATION_JSON).content(itemBody("ex-1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/api/items").session(editor)
                        .contentType(MediaType.APPLICATION_JSON).content(itemBody("ex-2")))
                .andExpect(status().isOk());
        long keepId = Long.parseLong(json1.replaceAll(".*\"id\":(\\d+).*", "$1"));
        // 第二件作废 → 报告口径排除
        mockMvc.perform(post("/api/items/{id}/void", keepId + 1).session(editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReqId\":\"exv-1\",\"reason\":\"登録ミス\"}"))
                .andExpect(status().isOk());

        byte[] exported = export(editor, "2026-01-01", "2026-12-31", null);
        assertThat(new String(exported, 0, 2, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("PK");

        List<List<Object>> rows = readAll(exported, 0);
        // 行 1=25 列表头；数据仅存 1 件（作废排除）
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).stream().filter(Objects::nonNull).count()).isEqualTo(25);
        assertThat(ExcelRowParser.cellText(rows.get(0).get(0))).isEqualTo("管理番号");
        assertThat(rows.get(1).stream().map(ExcelRowParser::cellText))
                .contains("HT9-A1X", "名古屋", "移動中", "未出品", "1000", "1500")
                .doesNotContain("HT9-A2X");

        // 空结果区间=仅表头行（倒挂+无码是 400，见下方同步断言——不与成功路径混用）
        byte[] empty = export(editor, "2027-01-01", "2027-12-31", null);
        assertThat(readAll(empty, 0)).hasSize(1);

        byte[] single = export(editor, "2026-12-31", "2026-01-01", "HT9-A1X");
        assertThat(readAll(single, 0)).hasSize(2);

        // 无码条件时倒挂区间=400（校验在响应头提交前同步抛出，不经 async）
        mockMvc.perform(get("/api/excel/items/export")
                        .param("createdFrom", "2026-12-31").param("createdTo", "2026-01-01")
                        .session(editor))
                .andExpect(status().isBadRequest());

        // V 角色可见成本/利润（A19 需求字面默认）
        MockHttpSession viewer = loginAs("miru");
        assertThat(export(viewer, "2026-01-01", "2026-12-31", null)).isNotEmpty();
    }

    @Test
    void concurrentImportAndGeneration_invariantsHold() throws Exception {
        MockHttpSession editor = loginAs("eichi");
        // 旧号用 Z 档字母（生成器只会发 X/Y）→ 撞库只可能同码同字母，codeIsFree 拦为行错误
        // 类级 max-rows=3（绑定修复后真正生效）：导入侧 3 行旧号
        List<List<Object>> rows = new ArrayList<>();
        for (int seq = 10; seq <= 12; seq++) {
            rows.add(dataRow("HT9-A" + seq + "Z", "HT", "2026-09-01", 12000, "1"));
        }
        long batchId = upload(editor, xlsx(null, rows), "concurrent.xlsx");

        // 批处理同时 5 路连续录入同桶：服务层直调（MockMvc 非线程安全，镜像
        // ItemCodeIntegrationTest 的 runAs+create 模式——同一条引擎链路，无 REST 差异）
        long operatorId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'eichi'", Long.class);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                // client_req_id CHAR(36)：裸 UUID（36 字符）——加前缀 39 字符会截断报错
                String clientReqId = UUID.randomUUID().toString();
                futures.add(pool.submit(() -> {
                    runAs(operatorId, "eichi");
                    return itemCodeService.create(new CreateItemCommand(
                            clientReqId, null, venueId, LocalDate.of(2026, 9, 15), null, 1000L,
                            null, null, null, 1, null, null, null, null, null, null, null,
                            null, null, null, operatorId, "eichi")).getItemCode();
                }));
            }
            List<String> codes = new ArrayList<>();
            for (Future<String> future : futures) {
                codes.add(future.get());
            }
            assertThat(codes).doesNotHaveDuplicates();
        } finally {
            pool.shutdownNow();
        }
        String report = awaitBatch(editor, batchId);

        // 不变量：批内旧号零错误；全库 8 件码唯一；cur_seq==MAX(seq_no)
        assertThat(report).contains("\"status\":1").contains("\"importedCount\":3")
                .contains("\"errorCount\":0");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM item", Long.class)).isEqualTo(8);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) - COUNT(DISTINCT item_code) FROM item", Long.class)).isZero();
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq_no) FROM item WHERE venue_id = ? AND buy_month = 9",
                Integer.class, venueId);
        Integer curSeq = jdbcTemplate.queryForObject(
                "SELECT cur_seq FROM seq_item_code WHERE venue_id = ? AND month = 9",
                Integer.class, venueId);
        assertThat(curSeq).isEqualTo(maxSeq);
    }

    @Test
    void zombieBatch_recoveredOnStartupSweep() {
        jdbcTemplate.update("""
                INSERT INTO excel_import_batch(file_sha256, original_filename, status, row_count,
                    generated_count, imported_count, error_count, uploaded_by, created_at)
                VALUES ('deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef',
                    'zombie.xlsx', 0, 0, 0, 0, 0, 1, '2026-09-01 00:00:00')
                """);
        excelImportService.recoverZombieBatches();
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM excel_import_batch WHERE file_sha256 LIKE 'deadbeef%'", Integer.class);
        assertThat(status).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_message FROM excel_import_batch WHERE file_sha256 LIKE 'deadbeef%'",
                String.class)).isNotBlank();
    }
}
