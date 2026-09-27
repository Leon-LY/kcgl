package com.kcgl.module.inventory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 库存流水（只增不改不删）：operator 为显式传参快照（异步线程无 SecurityContext，docs/01 5.3）。 */
@TableName("stock_ledger")
public class StockLedgerEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String clientReqId;
    private Integer txnType;
    private Long itemId;
    private String itemCode;
    private Integer stockFrom;
    private Integer stockTo;
    private Integer saleFrom;
    private Integer saleTo;
    private Integer whFrom;
    private Integer whTo;
    private Integer qtyChange;
    private String reason;
    private Integer returnDirection;
    private String refType;
    private Long refId;
    private Long operatorId;
    private String operatorName;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getClientReqId() { return clientReqId; }
    public void setClientReqId(String clientReqId) { this.clientReqId = clientReqId; }
    public Integer getTxnType() { return txnType; }
    public void setTxnType(Integer txnType) { this.txnType = txnType; }
    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }
    public String getItemCode() { return itemCode; }
    public void setItemCode(String itemCode) { this.itemCode = itemCode; }
    public Integer getStockFrom() { return stockFrom; }
    public void setStockFrom(Integer stockFrom) { this.stockFrom = stockFrom; }
    public Integer getStockTo() { return stockTo; }
    public void setStockTo(Integer stockTo) { this.stockTo = stockTo; }
    public Integer getSaleFrom() { return saleFrom; }
    public void setSaleFrom(Integer saleFrom) { this.saleFrom = saleFrom; }
    public Integer getSaleTo() { return saleTo; }
    public void setSaleTo(Integer saleTo) { this.saleTo = saleTo; }
    public Integer getWhFrom() { return whFrom; }
    public void setWhFrom(Integer whFrom) { this.whFrom = whFrom; }
    public Integer getWhTo() { return whTo; }
    public void setWhTo(Integer whTo) { this.whTo = whTo; }
    public Integer getQtyChange() { return qtyChange; }
    public void setQtyChange(Integer qtyChange) { this.qtyChange = qtyChange; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Integer getReturnDirection() { return returnDirection; }
    public void setReturnDirection(Integer returnDirection) { this.returnDirection = returnDirection; }
    public String getRefType() { return refType; }
    public void setRefType(String refType) { this.refType = refType; }
    public Long getRefId() { return refId; }
    public void setRefId(Long refId) { this.refId = refId; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
