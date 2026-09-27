package com.kcgl.module.setting;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 键值系统设置（V1 收敛后 5 项 + checklist.print_done）。
 * key 为 MySQL 保留字，列映射带反引号；主键为自然键非自增（IdType.INPUT）。
 */
@TableName("sys_setting")
public class SysSettingEntity {

    @TableId(value = "`key`", type = IdType.INPUT)
    private String key;
    private String value;
    private Long updatedBy;
    private LocalDateTime updatedAt;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
