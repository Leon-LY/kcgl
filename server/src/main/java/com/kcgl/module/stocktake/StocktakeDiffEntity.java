package com.kcgl.module.stocktake;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 盘点差异（docs/01 5.3/7.3）：diff_type 1盘亏/2盘盈/3仓库不符/4冻结品；
 * confirm_status 0待确认/1确认调整/2忽略——人工确认才调账。
 * adjust_ledger_id=CONFIRM 生成的 STOCKTAKE_ADJUST 流水回链。
 */
@TableName("stocktake_diff")
public class StocktakeDiffEntity {

    /** 盘亏：期望在库未扫到（实物缺失）。 */
    public static final int TYPE_LOSS = 1;
    /** 盘盈：扫到但系统非在库。 */
    public static final int TYPE_GAIN = 2;
    /** 仓库不符：在库但系统仓≠盘点仓（实物在盘点仓）。 */
    public static final int TYPE_WH_MISMATCH = 3;
    /** 冻结品：已作废/回收站件被扫到——禁止 CONFIRM，仅可 IGNORE/线下处理。 */
    public static final int TYPE_FROZEN = 4;

    public static final int CONFIRM_PENDING = 0;
    public static final int CONFIRM_ADJUSTED = 1;
    public static final int CONFIRM_IGNORED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long stocktakeId;
    private Long itemId;
    private String itemCode;
    private Integer diffType;
    private Integer expectedWh;
    private Integer actualWh;
    private String note;
    private Integer confirmStatus;
    private Long adjustLedgerId;
    private Long confirmedBy;
    private LocalDateTime confirmedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStocktakeId() { return stocktakeId; }
    public void setStocktakeId(Long stocktakeId) { this.stocktakeId = stocktakeId; }
    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }
    public String getItemCode() { return itemCode; }
    public void setItemCode(String itemCode) { this.itemCode = itemCode; }
    public Integer getDiffType() { return diffType; }
    public void setDiffType(Integer diffType) { this.diffType = diffType; }
    public Integer getExpectedWh() { return expectedWh; }
    public void setExpectedWh(Integer expectedWh) { this.expectedWh = expectedWh; }
    public Integer getActualWh() { return actualWh; }
    public void setActualWh(Integer actualWh) { this.actualWh = actualWh; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Integer getConfirmStatus() { return confirmStatus; }
    public void setConfirmStatus(Integer confirmStatus) { this.confirmStatus = confirmStatus; }
    public Long getAdjustLedgerId() { return adjustLedgerId; }
    public void setAdjustLedgerId(Long adjustLedgerId) { this.adjustLedgerId = adjustLedgerId; }
    public Long getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(Long confirmedBy) { this.confirmedBy = confirmedBy; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
}
