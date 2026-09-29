package com.kcgl.module.yahoo;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 雅虎受注记录（一订单商品一行，(item_id, yahoo_auction_id) 对幂等——まとめ売り
 * 同拍卖多件多行，D-069）。受注表=成交事实集：status 恒 2、closed_at=OrderTime；
 * 未匹配行 item_id=NULL（占位，商品后录入时由导入收养）。
 */
@Data
@TableName("yahoo_listing")
public class YahooListingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String yahooAuctionId;
    /** 雅虎受注 ID（A 列，留痕展示）。 */
    private String orderId;
    private Long itemId;
    /** 受注文件原文自码（未匹配行展示原文码）。 */
    private String rawItemCode;
    private String itemCode;
    /** 受注表无出品价，恒 NULL（列保留兼容历史数据）。 */
    private Long listPrice;
    private Long soldPrice;
    /** 受注导入恒 2（成交）；1/3 仅存在于 CSV 时代历史行（docs/01 5.3）。 */
    private Integer status;
    /** 受注表无出品时刻，恒 NULL。 */
    private LocalDateTime listedAt;
    /** 受注（成交）时刻=C 列 OrderTime。 */
    private LocalDateTime closedAt;
    private Long firstSeenBatchId;
    private Long lastSeenBatchId;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
