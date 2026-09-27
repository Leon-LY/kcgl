package com.kcgl.module.inventory;

/**
 * 对账下钻查询结果（MyBatis 驼峰映射，stock_ledger GROUP BY 行）：
 * 一件在一仓的流水净头寸。合法历史恒为 -1/0/+1（头寸表只回非零行）；
 * 出现 ±2 或双仓同正=流水本身异常，同样会被比对为漂移上报。
 */
public class LedgerItemPosition {

    private Long itemId;
    private Integer warehouse;
    private Long net;

    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }
    public Integer getWarehouse() { return warehouse; }
    public void setWarehouse(Integer warehouse) { this.warehouse = warehouse; }
    public Long getNet() { return net; }
    public void setNet(Long net) { this.net = net; }
}
