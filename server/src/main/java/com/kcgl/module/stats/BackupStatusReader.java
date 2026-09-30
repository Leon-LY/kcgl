package com.kcgl.module.stats;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * backup-status.json 读取器（M7 增量，D-077 决策 5 预留契约的兑现）：
 * backup.sh 每次运行落状态文件，系统状况页展示「直近バックアップ成功時刻」。
 * 文件由外部脚本产出=不可信输入——任何异常（缺文件/坏 JSON/缺字段/时刻不解析）
 * 一律 Optional.empty（诊断页绝不因状态文件损坏而 500），null 语义=不可知，
 * 前端渲染占位。未配置路径（开发环境默认）同样 empty=功能关闭。
 * lastSuccessAt 为 date -Is 格式（带 offset）——backup.sh 在宿主机执行，
 * 宿主时区任意，时刻自描述故跨时区安全。
 */
@Component
public class BackupStatusReader {

    /**
     * lastSuccessAt=原样回显（date -Is 带 offset，诊断导出保留宿主时区原貌）；
     * lastSuccessAtJst=naive JST 墙钟（前端展示专用——dayjs.tz 对带 offset 输入
     * 会吞掉后缀按 UTC 解析，实测确认，故服务端换算）；staleSeconds 供前端判 25h
     * 陈旧（与 kcgl-doctor 同阈值）；detail=脚本摘要（db 尺寸等）。
     */
    public record BackupStatus(String lastSuccessAt, java.time.LocalDateTime lastSuccessAtJst,
            long staleSeconds, String detail) {
    }

    private final Path statusPath;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public BackupStatusReader(@Value("${kcgl.backup.status-path:}") String statusPath,
            ObjectMapper objectMapper, Clock clock) {
        this.statusPath = statusPath == null || statusPath.isBlank() ? null : Path.of(statusPath);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Optional<BackupStatus> read() {
        if (statusPath == null) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(statusPath));
            String lastSuccessAt = root.path("lastSuccessAt").asString("");
            // 跑过但从未成功（lastErrorAt 有值）也 empty——展示语义是「成功时间」
            if (lastSuccessAt.isBlank()) {
                return Optional.empty();
            }
            OffsetDateTime at = OffsetDateTime.parse(lastSuccessAt.trim());
            return Optional.of(new BackupStatus(
                    lastSuccessAt,
                    java.time.LocalDateTime.ofInstant(at.toInstant(), clock.getZone()),
                    Duration.between(at, OffsetDateTime.now(clock)).toSeconds(),
                    root.path("detail").asString("")));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
