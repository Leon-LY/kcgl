package com.kcgl.module.stocktake;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 盘点扫码（docs/01 5.3）：uk(stocktake_id, item_id)——重复扫码不报错（repeated=true），
 * scanned_by 即「谁扫」留痕（docs/01 7.3 双留痕，逐条扫码不再另写 operation_log）。
 */
@TableName("stocktake_scan")
public class StocktakeScanEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long stocktakeId;
    private Long itemId;
    private String itemCode;
    private Long scannedBy;
    private LocalDateTime scannedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStocktakeId() { return stocktakeId; }
    public void setStocktakeId(Long stocktakeId) { this.stocktakeId = stocktakeId; }
    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }
    public String getItemCode() { return itemCode; }
    public void setItemCode(String itemCode) { this.itemCode = itemCode; }
    public Long getScannedBy() { return scannedBy; }
    public void setScannedBy(Long scannedBy) { this.scannedBy = scannedBy; }
    public LocalDateTime getScannedAt() { return scannedAt; }
    public void setScannedAt(LocalDateTime scannedAt) { this.scannedAt = scannedAt; }
}
