package com.kcgl.common.obs;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 诊断导出（GET /api/diagnostics/export，M5-④）：管理员专用，JSON 附件下载。
 * 甲方动作=点按钮发文件（docs/01 9.3）——排障材料一次打包，字段全白名单。
 */
@RestController
public class DiagnosticsController {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final DiagnosticsExportService exportService;
    private final ObjectMapper objectMapper;

    public DiagnosticsController(DiagnosticsExportService exportService, ObjectMapper objectMapper) {
        this.exportService = exportService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/diagnostics/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> export() {
        DiagnosticsExport payload = exportService.build();
        String filename = "kcgl-diagnostics-"
                + LocalDateTime.now().format(FILE_STAMP) + ".json";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok()
                .headers(headers)
                .body(objectMapper.writeValueAsBytes(payload));
    }
}
