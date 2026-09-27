package com.kcgl.module.log;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 前端错误上报（V1__init.sql client_error）：window.onerror/unhandledrejection/Vue errorHandler/
 * 上传队列超阈值 → 前端 POST；管理员可查，30 天清理 job（M5）。errorId 与后端 500 的
 * errorId（=traceId）同源闭环。
 */
@Data
@TableName("client_error")
public class ClientErrorEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String message;
    private String stack;
    private String route;
    private String ua;
    private String locale;
    private String appVersion;
    private Long userId;
    private String errorId;
    private Integer queuePending;
    private Integer queueOldestAgeSec;
    private LocalDateTime createdAt;
}
