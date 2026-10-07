package com.kcgl.common.obs;

import com.kcgl.common.i18n.Msg;
import com.kcgl.common.i18n.MsgJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 告警写入口（docs/01 9.3）：对账 job/磁盘水位/备份标记/证书检查等系统侧告警统一落 sys_alert。
 * 无认证语义（调用方多为后台任务）——与 operation_log（必须有人）不同，本表记录系统状态。
 * 调用方同时打 WARN/ERROR 日志（带 MDC traceId），表用于管理后台红点/横幅的持久化展示。
 *
 * <p>入参是 {@link Msg}（code+params+日文原文）而非裸字符串（V6，D-128）：告警文案同样要
 * 随界面语言走，而历史实现只落日文句子、管理端原样渲染。原文继续入 message 列（NOT NULL，
 * 供旧行兜底、server log 与诊断包导出），code/params 另行落 message_key/message_params。
 * 参数类型即 {@link Msg} 而非「字符串 + 可选键」的重载：让漏写键在编译期就暴露。
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    public static final int LEVEL_INFO = 1;
    public static final int LEVEL_WARN = 2;
    public static final int LEVEL_ERROR = 3;

    private final SysAlertMapper mapper;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public AlertService(SysAlertMapper mapper, Clock clock, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /** 同 dedupKey 告警只留最新一条开启态（先 upsert 覆盖，已读状态一并复位）。 */
    public void record(String type, int level, Msg msg, String dedupKey, String payloadJson) {
        mapper.upsertOpen(type, dedupKey, level, msg.text(), msg.code(),
                MsgJson.paramsOf(msg, objectMapper), payloadJson, LocalDateTime.now(clock));
        if (level >= LEVEL_ERROR) {
            log.error("系统告警 [{}] {}: {}", type, dedupKey, msg.text());
        } else {
            log.warn("系统告警 [{}] {}: {}", type, dedupKey, msg.text());
        }
    }
}
