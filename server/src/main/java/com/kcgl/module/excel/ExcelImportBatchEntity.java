package com.kcgl.module.excel;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Excel 导入批次（V2 表；file_sha256 唯一=同文件重传 409）。双模式计数：
 * generated=批量生成模式（管理番号列空）、imported=旧号导入模式（管理番号列有值）；
 * error_rows 为采样 JSON（前 1000 条+计数）；note 记录计数器跳变说明（D-058 D）。
 */
@Data
@TableName("excel_import_batch")
public class ExcelImportBatchEntity {

    public static final int STATUS_PROCESSING = 0;
    public static final int STATUS_DONE = 1;
    public static final int STATUS_FAILED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String fileSha256;
    private String originalFilename;
    private Integer status;
    private Integer rowCount;
    private Integer generatedCount;
    private Integer importedCount;
    private Integer errorCount;
    /** 错误行采样（Jackson 序列化的 [{line,raw,reason}]，前 1000 条）。 */
    @TableField("error_rows")
    private String errorRowsJson;
    /** 计数器跳变说明（位置序推进的桶迁移，如「HT2026-9 A5→A12」；多跳「、」连接）。 */
    private String note;
    private String errorMessage;
    /** errorMessage 的结构化形态：消息键 + 插值参数 JSON（可空＝无对应键，直出原文）。 */
    @TableField("error_message_code")
    private String errorMessageCode;
    @TableField("error_message_params")
    private String errorMessageParams;
    private Long uploadedBy;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
