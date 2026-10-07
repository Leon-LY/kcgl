package com.kcgl.common.obs;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统一告警（V1__init.sql sys_alert）：解决「带内告警与被监控系统同生共死」的第一层落库持久化
 * （管理后台红点+登录页横幅+SSE 在线推送）。同 dedup_key 去重只留最新一条开启态（uk_dedup）。
 */
@Data
@TableName("sys_alert")
public class SysAlertEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String type;
    private String dedupKey;
    private Integer level;      // 1提示 2警告 3错误
    private String message;
    /**
     * 文案 i18n 键与插值参数（V6，D-128）：消息键为空即历史行（V6 之前），前端回退
     * {@link #message} 的日文原文。params 是 JSON 列，在实体上落为 String（与
     * yahoo/excel 批次表同口径），前端读回后自行 parse。
     */
    private String messageKey;
    private String messageParams;
    private String payload;     // JSON 文本
    private Integer status;     // 0开启 1已读
    private Long readBy;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
