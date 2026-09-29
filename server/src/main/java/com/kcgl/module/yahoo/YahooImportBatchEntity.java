package com.kcgl.module.yahoo;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 受注 xlsx 导入批次（file_sha256 唯一=同文件重传 409；error_rows 为采样 JSON：
 * 前 1000 条+计数；note=まとめ売り等批次级補足，500 字截断）。
 */
@Data
@TableName("yahoo_import_batch")
public class YahooImportBatchEntity {

    public static final int STATUS_PROCESSING = 0;
    public static final int STATUS_DONE = 1;
    public static final int STATUS_FAILED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String fileSha256;
    private String originalFilename;
    private Integer status;
    private Integer rowCount;
    private Integer matchedCount;
    private Integer unmatchedCount;
    private Integer updatedCount;
    /** 错误行采样（Jackson 序列化的 [{line,raw,reason}]，前 1000 条）。 */
    @TableField("error_rows")
    private String errorRowsJson;
    /** まとめ売り「複数商品のため単価未分割」等批次级補足（「、」连接，500 字截断）。 */
    private String note;
    private String errorMessage;
    private Long uploadedBy;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
