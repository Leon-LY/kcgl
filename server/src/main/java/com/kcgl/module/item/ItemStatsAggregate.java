package com.kcgl.module.item;

/**
 * 两仓/大盘聚合查询结果（MyBatis 驼峰映射，M5-③ stats）：
 * 条件聚合单行（空表也回一行——SUM 经 COALESCE 归零、AVG 为 NULL）。
 * 口径唯一定义见 ItemMapper#aggregateStats javadoc。
 */
public class ItemStatsAggregate {

    private Long totalItems;
    private Long inTransit;
    private Long inStock;
    private Long shipped;
    private Long monthInbound;
    private Long slowWarn;
    private Long slowRed;
    private Long stockValue;
    private Double avgStockAgeDays;

    public Long getTotalItems() { return totalItems; }
    public void setTotalItems(Long totalItems) { this.totalItems = totalItems; }
    public Long getInTransit() { return inTransit; }
    public void setInTransit(Long inTransit) { this.inTransit = inTransit; }
    public Long getInStock() { return inStock; }
    public void setInStock(Long inStock) { this.inStock = inStock; }
    public Long getShipped() { return shipped; }
    public void setShipped(Long shipped) { this.shipped = shipped; }
    public Long getMonthInbound() { return monthInbound; }
    public void setMonthInbound(Long monthInbound) { this.monthInbound = monthInbound; }
    public Long getSlowWarn() { return slowWarn; }
    public void setSlowWarn(Long slowWarn) { this.slowWarn = slowWarn; }
    public Long getSlowRed() { return slowRed; }
    public void setSlowRed(Long slowRed) { this.slowRed = slowRed; }
    public Long getStockValue() { return stockValue; }
    public void setStockValue(Long stockValue) { this.stockValue = stockValue; }
    public Double getAvgStockAgeDays() { return avgStockAgeDays; }
    public void setAvgStockAgeDays(Double avgStockAgeDays) { this.avgStockAgeDays = avgStockAgeDays; }
}
