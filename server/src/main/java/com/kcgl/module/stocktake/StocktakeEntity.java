package com.kcgl.module.stocktake;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 盘点单（docs/01 5.3）：状态 0进行中/1待确认（已 close）/2已确认/3作废。
 * expected_count/diff_count 仅 close 时冻结写入；scanned_count 为相对递增计数。
 */
@TableName("stocktake")
public class StocktakeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String stocktakeNo;
    private Integer warehouse;
    private Integer status;
    private Integer expectedCount;
    private Integer scannedCount;
    private Integer diffCount;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime closedAt;
    private Long closedBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStocktakeNo() { return stocktakeNo; }
    public void setStocktakeNo(String stocktakeNo) { this.stocktakeNo = stocktakeNo; }
    public Integer getWarehouse() { return warehouse; }
    public void setWarehouse(Integer warehouse) { this.warehouse = warehouse; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getExpectedCount() { return expectedCount; }
    public void setExpectedCount(Integer expectedCount) { this.expectedCount = expectedCount; }
    public Integer getScannedCount() { return scannedCount; }
    public void setScannedCount(Integer scannedCount) { this.scannedCount = scannedCount; }
    public Integer getDiffCount() { return diffCount; }
    public void setDiffCount(Integer diffCount) { this.diffCount = diffCount; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public Long getClosedBy() { return closedBy; }
    public void setClosedBy(Long closedBy) { this.closedBy = closedBy; }
}
