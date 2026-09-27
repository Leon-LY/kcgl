package com.kcgl.module.log;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志（V1__init.sql operation_log）：只增不改不删（验收 9）。
 * 不可变性双保险：无任何改删端点（契约测试固化）+ DB 账号仅 SELECT,INSERT（部署层 GRANT）。
 * detail 为 JSON 文本（前后值 diff 等）；密码哈希/明文严禁入内（契约测试断言）。
 */
@Data
@TableName("operation_log")
public class OperationLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String action;
    private String entityType;
    private Long entityId;
    private String detail;
    private Long operatorId;
    private String operatorName;
    private String ip;
    private String ua;
    private LocalDateTime createdAt;
}
