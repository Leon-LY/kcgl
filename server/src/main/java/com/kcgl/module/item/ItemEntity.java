package com.kcgl.module.item;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 商品主表（docs/01 5.1）。号内快照列（venue_code/year_code/buy_month/seq_prefix/seq_no/
 * price_band_code）由管理号引擎在生成时写入，此后恒不变（A0 快照单模式，D 决策）。
 * total_cost/profit 为 STORED 生成列：insert/update 一律 NEVER（MySQL 3105 经典坑，
 * 集成测试读改写覆盖）。
 */
@TableName("item")
public class ItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String itemCode;
    private Long venueId;
    private String venueCode;
    private Integer year;
    private String yearCode;
    private Integer buyMonth;
    private String seqPrefix;
    private Integer seqNo;
    private LocalDate buyDate;
    private LocalDate photoDate;
    private Long purchasePrice;
    private Long fee;
    private Long shippingFee;
    private Long tax;
    private Long soldPrice;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long totalCost;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long profit;
    private String priceBandCode;
    private Integer warehouse;
    private String shelfNo;
    private LocalDate warehouseInDate;
    private String groupNo;
    private String remark;
    private String itemName;
    private String category;
    private String authorKiln;
    private String sizeText;
    private Integer weightG;
    private String salesChannel;
    private Long reEntryOf;
    private String extJson;
    private Integer stockStatus;
    private Integer saleStatus;
    private String yahooItemId;
    private Long listPrice;
    private Integer yahooListingCount;
    private Integer yahooCancelCount;
    private LocalDateTime yahooLastSyncedAt;
    private Integer voided;
    private String voidReason;
    private Long voidReEntry;
    private Integer deleted;
    private LocalDateTime deletedAt;
    private Long deletedBy;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
    private Integer version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getItemCode() { return itemCode; }
    public void setItemCode(String itemCode) { this.itemCode = itemCode; }
    public Long getVenueId() { return venueId; }
    public void setVenueId(Long venueId) { this.venueId = venueId; }
    public String getVenueCode() { return venueCode; }
    public void setVenueCode(String venueCode) { this.venueCode = venueCode; }
    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }
    public String getYearCode() { return yearCode; }
    public void setYearCode(String yearCode) { this.yearCode = yearCode; }
    public Integer getBuyMonth() { return buyMonth; }
    public void setBuyMonth(Integer buyMonth) { this.buyMonth = buyMonth; }
    public String getSeqPrefix() { return seqPrefix; }
    public void setSeqPrefix(String seqPrefix) { this.seqPrefix = seqPrefix; }
    public Integer getSeqNo() { return seqNo; }
    public void setSeqNo(Integer seqNo) { this.seqNo = seqNo; }
    public LocalDate getBuyDate() { return buyDate; }
    public void setBuyDate(LocalDate buyDate) { this.buyDate = buyDate; }
    public LocalDate getPhotoDate() { return photoDate; }
    public void setPhotoDate(LocalDate photoDate) { this.photoDate = photoDate; }
    public Long getPurchasePrice() { return purchasePrice; }
    public void setPurchasePrice(Long purchasePrice) { this.purchasePrice = purchasePrice; }
    public Long getFee() { return fee; }
    public void setFee(Long fee) { this.fee = fee; }
    public Long getShippingFee() { return shippingFee; }
    public void setShippingFee(Long shippingFee) { this.shippingFee = shippingFee; }
    public Long getTax() { return tax; }
    public void setTax(Long tax) { this.tax = tax; }
    public Long getSoldPrice() { return soldPrice; }
    public void setSoldPrice(Long soldPrice) { this.soldPrice = soldPrice; }
    public Long getTotalCost() { return totalCost; }
    public void setTotalCost(Long totalCost) { this.totalCost = totalCost; }
    public Long getProfit() { return profit; }
    public void setProfit(Long profit) { this.profit = profit; }
    public String getPriceBandCode() { return priceBandCode; }
    public void setPriceBandCode(String priceBandCode) { this.priceBandCode = priceBandCode; }
    public Integer getWarehouse() { return warehouse; }
    public void setWarehouse(Integer warehouse) { this.warehouse = warehouse; }
    public String getShelfNo() { return shelfNo; }
    public void setShelfNo(String shelfNo) { this.shelfNo = shelfNo; }
    public LocalDate getWarehouseInDate() { return warehouseInDate; }
    public void setWarehouseInDate(LocalDate warehouseInDate) { this.warehouseInDate = warehouseInDate; }
    public String getGroupNo() { return groupNo; }
    public void setGroupNo(String groupNo) { this.groupNo = groupNo; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getAuthorKiln() { return authorKiln; }
    public void setAuthorKiln(String authorKiln) { this.authorKiln = authorKiln; }
    public String getSizeText() { return sizeText; }
    public void setSizeText(String sizeText) { this.sizeText = sizeText; }
    public Integer getWeightG() { return weightG; }
    public void setWeightG(Integer weightG) { this.weightG = weightG; }
    public String getSalesChannel() { return salesChannel; }
    public void setSalesChannel(String salesChannel) { this.salesChannel = salesChannel; }
    public Long getReEntryOf() { return reEntryOf; }
    public void setReEntryOf(Long reEntryOf) { this.reEntryOf = reEntryOf; }
    public String getExtJson() { return extJson; }
    public void setExtJson(String extJson) { this.extJson = extJson; }
    public Integer getStockStatus() { return stockStatus; }
    public void setStockStatus(Integer stockStatus) { this.stockStatus = stockStatus; }
    public Integer getSaleStatus() { return saleStatus; }
    public void setSaleStatus(Integer saleStatus) { this.saleStatus = saleStatus; }
    public String getYahooItemId() { return yahooItemId; }
    public void setYahooItemId(String yahooItemId) { this.yahooItemId = yahooItemId; }
    public Long getListPrice() { return listPrice; }
    public void setListPrice(Long listPrice) { this.listPrice = listPrice; }
    public Integer getYahooListingCount() { return yahooListingCount; }
    public void setYahooListingCount(Integer yahooListingCount) { this.yahooListingCount = yahooListingCount; }
    public Integer getYahooCancelCount() { return yahooCancelCount; }
    public void setYahooCancelCount(Integer yahooCancelCount) { this.yahooCancelCount = yahooCancelCount; }
    public LocalDateTime getYahooLastSyncedAt() { return yahooLastSyncedAt; }
    public void setYahooLastSyncedAt(LocalDateTime yahooLastSyncedAt) { this.yahooLastSyncedAt = yahooLastSyncedAt; }
    public Integer getVoided() { return voided; }
    public void setVoided(Integer voided) { this.voided = voided; }
    public String getVoidReason() { return voidReason; }
    public void setVoidReason(String voidReason) { this.voidReason = voidReason; }
    public Long getVoidReEntry() { return voidReEntry; }
    public void setVoidReEntry(Long voidReEntry) { this.voidReEntry = voidReEntry; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public Long getDeletedBy() { return deletedBy; }
    public void setDeletedBy(Long deletedBy) { this.deletedBy = deletedBy; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
