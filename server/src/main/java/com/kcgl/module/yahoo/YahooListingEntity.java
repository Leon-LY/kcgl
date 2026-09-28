package com.kcgl.module.yahoo;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 雅虎出品记录（一次出品一行，yahoo_auction_id 行级幂等键）。
 */
@Data
@TableName("yahoo_listing")
public class YahooListingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String yahooAuctionId;
    private Long itemId;
    /** CSV 原文管理号（未匹配行展示原文码）。 */
    private String rawItemCode;
    private String itemCode;
    private Long listPrice;
    private Long soldPrice;
    /** 1在售 2成交 3取消（docs/01 5.3）。 */
    private Integer status;
    private LocalDateTime listedAt;
    private LocalDateTime closedAt;
    private Long firstSeenBatchId;
    private Long lastSeenBatchId;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
