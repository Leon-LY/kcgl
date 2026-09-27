package com.kcgl.common.obs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 告警写入口（docs/01 9.3）：对账 job/磁盘水位/备份标记/证书检查等系统侧告警统一落 sys_alert。
 * 无认证语义（调用方多为后台任务）——与 operation_log（必须有人）不同，本表记录系统状态。
 * 调用方同时打 WARN/ERROR 日志（带 MDC traceId），表用于管理后台红点/横幅的持久化展示。
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    public static final int LEVEL_INFO = 1;
    public static final int LEVEL_WARN = 2;
    public static final int LEVEL_ERROR = 3;

    private final SysAlertMapper mapper;
    private final Clock clock;

    public AlertService(SysAlertMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 同 dedupKey 告警只留最新一条开启态（先 upsert 覆盖，已读状态一并复位）。 */
    public void record(String type, int level, String message, String dedupKey, String payloadJson) {
        mapper.upsertOpen(type, dedupKey, level, message, payloadJson, LocalDateTime.now(clock));
        if (level >= LEVEL_ERROR) {
            log.error("系统告警 [{}] {}: {}", type, dedupKey, message);
        } else {
            log.warn("系统告警 [{}] {}: {}", type, dedupKey, message);
        }
    }
}
