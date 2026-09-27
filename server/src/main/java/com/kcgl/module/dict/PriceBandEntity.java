package com.kcgl.module.dict;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 价格档位（字典）：左闭右开 [lower, upper)；NULL=无界端（首档无下限/末档无上限）。
 * 只停用不删——item.price_band_code 是录入时快照，不回溯历史。
 */
@TableName("price_band")
public class PriceBandEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private Long lowerBound;
    private Long upperBound;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Long getLowerBound() { return lowerBound; }
    public void setLowerBound(Long lowerBound) { this.lowerBound = lowerBound; }
    public Long getUpperBound() { return upperBound; }
    public void setUpperBound(Long upperBound) { this.upperBound = upperBound; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
