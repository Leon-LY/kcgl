package com.kcgl.module.excel;

import cn.idev.excel.ExcelWriter;
import cn.idev.excel.FastExcel;
import cn.idev.excel.write.metadata.WriteSheet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Excel 导入错误报告的**采样上界与总数分离**（审计项 A3 后半程，docs/04 D-110）。
 *
 * <p>为什么单开一类：{@code ExcelIntegrationTest} 类级把 {@code max-rows} 压到 3（行数上限
 * 用例所需），而本用例要错误行数**超过采样上限**才能观测「采样截断、总数不截断」，
 * 须自带一个 {@code max-rows} 保持默认（20000）的上下文。
 *
 * <p>守的是本次修复最容易被改坏的一处：给采样列表设界的直觉写法是解析期直接
 * {@code if (errors.size() < LIMIT) errors.add(...)}——那会连 {@code errorCount} 一起截成 1000，
 * 用户看到的「错误行数」被少报。正确做法是**计数恒增、列表按上限截断**（见
 * {@code ImportRowListener#recordError}）。本用例以 {@code ERROR_SAMPLE_LIMIT + 200} 行坏行
 * 同时断言两者：计数=全部坏行，采样 JSON 恰好=上限。
 */
@SpringBootTest(properties = {
        "kcgl.security.allowed-origins=https://kcgl.example.com",
        "kcgl.excel.imports-dir=target/excel-sample-imports"})
@AutoConfigureMockMvc
@Testcontainers
class ExcelErrorSampleBoundTest {

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

    /** 坏行数：须 > 采样上限才能观测截断。 */
    static final int BAD_ROWS = ExcelImportService.ERROR_SAMPLE_LIMIT + 200;

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void resetFixtures() {
        jdbcTemplate.update("DELETE FROM operation_log");
        jdbcTemplate.update("DELETE FROM stock_ledger");
        jdbcTemplate.update("DELETE FROM excel_import_batch");
        jdbcTemplate.update("DELETE FROM item");
        jdbcTemplate.update("DELETE FROM seq_item_code");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = 'eichi'");
        jdbcTemplate.update("""
                INSERT INTO sys_user(username, password_hash, display_name, role, enabled, must_change_pwd)
                VALUES ('eichi', ?, '編集者', 2, 1, 0)
                """, ENCODER.encode(PASSWORD));
        jdbcTemplate.update("DELETE FROM auction_venue");
        jdbcTemplate.update("DELETE FROM price_band");
        jdbcTemplate.update("INSERT INTO price_band(code, lower_bound, upper_bound, enabled)"
                + " VALUES ('X', 0, 3000, 1), ('Y', 3000, 1000000, 1)");
        jdbcTemplate.update("INSERT INTO auction_venue(code, name, enabled) VALUES ('HT', '飛騨古民具市', 1)");
    }

    @Test
    @Tag("regression")
    void manyBadRows_errorCountIsTotalSamplesAreBounded() throws Exception {
        MockHttpSession editor = loginAs("eichi");

        long batchId = upload(editor, xlsxWithBadRows(BAD_ROWS), "many-bad-rows.xlsx");
        String report = awaitBatch(editor, batchId);

        // 坏行全部在解析期被拦（仕入単価非数値），一行都不落库 → 整批「完成但有错误」
        assertThat(report).contains("\"status\":1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_count FROM excel_import_batch WHERE id = ?", Integer.class, batchId))
                .as("错误行总数不得被采样上限截断（少报）")
                .isEqualTo(BAD_ROWS);

        String errorRows = jdbcTemplate.queryForObject(
                "SELECT error_rows FROM excel_import_batch WHERE id = ?", String.class, batchId);
        assertThat(errorRows).isNotNull();
        List<?> samples = objectMapper.readValue(errorRows, List.class);
        assertThat(samples)
                .as("错误行采样须按 ERROR_SAMPLE_LIMIT 截断（否则 20k 坏行的堆无界）")
                .hasSize(ExcelImportService.ERROR_SAMPLE_LIMIT);
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

    /** 每行都给仕入単価填非数值 → 逐行解析错误（解析期即拦，不进 DB，故上千行也快）。 */
    private static byte[] xlsxWithBadRows(int rows) throws IOException {
        List<List<String>> head = new ArrayList<>();
        for (String name : ExcelProperties.defaults().columns().headerOrder()) {
            head.add(List.of(name));
        }
        List<List<Object>> data = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            List<Object> cells = new ArrayList<>();
            for (int c = 0; c < 19; c++) {
                cells.add("");
            }
            cells.set(1, "HT");
            cells.set(2, "2026-09-01");
            cells.set(3, "not-a-number");
            cells.set(7, "1");
            data.add(cells);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = FastExcel.write(out).build()) {
            WriteSheet sheet = FastExcel.writerSheet(0, "商品").head(head).build();
            writer.write(data, sheet);
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

    /** 轮询批次至终态（处理段毫秒级，30s 上限防挂死）。 */
    private String awaitBatch(MockHttpSession session, long batchId) throws Exception {
        for (int i = 0; i < 300; i++) {
            String json = mockMvc.perform(get("/api/excel/items/imports/{id}", batchId).session(session))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            if (json.contains("\"status\":1") || json.contains("\"status\":2")) {
                return json;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("批次 30s 内未达终态: " + batchId);
    }
}
