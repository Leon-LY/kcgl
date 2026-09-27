package com.kcgl.module.dict;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 年份代号对照（2016=A 起不跳 I/O，V1 已种子至 2041=Z）；年份与代号双向唯一。 */
@TableName("year_code")
public class YearCodeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Integer year;
    private String code;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
