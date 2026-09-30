package com.kcgl.module.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * backup-status.json 读取器单测（M7 增量，D-077 决策 5 预留）：
 * 格式自 D-077 冻结（lastRunAt/lastSuccessAt/lastErrorAt/detail），
 * 由 backup.sh 产出。四态：未配置/文件缺失（从未运行）/坏 JSON（不炸，
 * 视为不可知）/正常（回显 lastSuccessAt+算 staleSeconds）。
 * lastSuccessAt 为 date -Is 格式（带 offset，宿主时区任意均可解析）。
 */
class BackupStatusReaderTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    @TempDir
    Path tempDir;

    private BackupStatusReader readerFor(String path, Clock clock) {
        return new BackupStatusReader(path, new ObjectMapper(), clock);
    }

    @Test
    void unconfiguredPathYieldsEmpty() {
        assertThat(readerFor("", Clock.system(JST)).read()).isEmpty();
        assertThat(readerFor(null, Clock.system(JST)).read()).isEmpty();
    }

    @Test
    void missingFileYieldsEmpty() {
        assertThat(readerFor(tempDir.resolve("nope.json").toString(), Clock.system(JST)).read())
                .as("从未运行过备份=文件不存在").isEmpty();
    }

    @Test
    void malformedJsonYieldsEmptyInsteadOfException() {
        Path bad = write("not json at all {{{");
        assertThat(readerFor(bad.toString(), Clock.system(JST)).read())
                .as("坏 JSON 不炸（诊断页不能因状态文件损坏而 500）").isEmpty();
    }

    @Test
    void emptyLastSuccessAtYieldsEmpty() {
        Path neverOk = write("{\"lastRunAt\":\"2026-09-30T01:00:00+09:00\","
                + "\"lastSuccessAt\":\"\",\"lastErrorAt\":\"2026-09-30T01:00:00+09:00\","
                + "\"detail\":\"disk usage 85% exceeds 80%\"}");
        assertThat(readerFor(neverOk.toString(), Clock.system(JST)).read())
                .as("跑过但从未成功=lastSuccessAt 空，不显示成功时间").isEmpty();
    }

    @Test
    void wellFormedStatusIsParsedWithStaleness() {
        // 2 小时前成功（JST offset）；宿主写入方可能是任意时区——用
        // -05:00 等价时刻验证跨 offset 解析
        OffsetDateTime twoHoursAgo = OffsetDateTime.now(Clock.system(JST)).minusHours(2);
        OffsetDateTime otherZone = twoHoursAgo.atZoneSameInstant(ZoneId.of("America/New_York"))
                .toOffsetDateTime();
        Path ok = write("{\"lastRunAt\":\"" + otherZone + "\",\"lastSuccessAt\":\"" + otherZone
                + "\",\"lastErrorAt\":\"\",\"detail\":\"db 1.2MiB\"}");

        var status = readerFor(ok.toString(), Clock.system(JST)).read();

        assertThat(status).isPresent();
        assertThat(status.get().lastSuccessAt()).isEqualTo(otherZone.toString());
        assertThat(status.get().detail()).isEqualTo("db 1.2MiB");
        assertThat(status.get().staleSeconds())
                .isBetween(Duration.ofHours(2).minusMinutes(1).toSeconds(),
                        Duration.ofHours(2).plusMinutes(1).toSeconds());
    }

    private Path write(String content) {
        try {
            Path file = Files.createTempFile(tempDir, "backup-status", ".json");
            Files.writeString(file, content);
            return file;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
