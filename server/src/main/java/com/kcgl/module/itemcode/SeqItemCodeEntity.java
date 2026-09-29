package com.kcgl.module.itemcode;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 管理号计数器（桶=会场+月，跨年连续，一桶一行 FOR UPDATE 行锁，docs/01 5.2/7.1；D-068）。 */
@TableName("seq_item_code")
public class SeqItemCodeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long venueId;
    private Integer month;
    private String curPrefix;
    private Integer curSeq;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVenueId() { return venueId; }
    public void setVenueId(Long venueId) { this.venueId = venueId; }
    public Integer getMonth() { return month; }
    public void setMonth(Integer month) { this.month = month; }
    public String getCurPrefix() { return curPrefix; }
    public void setCurPrefix(String curPrefix) { this.curPrefix = curPrefix; }
    public Integer getCurSeq() { return curSeq; }
    public void setCurSeq(Integer curSeq) { this.curSeq = curSeq; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
