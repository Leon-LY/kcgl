package com.kcgl.module.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账号表（V1__init.sql sys_user）。用户只停用（enabled=0）不物理删除。
 * 时间字段一律 LocalDateTime，JST 直存直读，禁 Instant/OffsetDateTime（docs/01 7.8）。
 */
@Data
@TableName("sys_user")
public class SysUserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    private String passwordHash;
    private String displayName;
    private Integer role;
    private String locale;
    private Integer enabled;
    private Integer mustChangePwd;
    private Integer failedAttempts;
    private LocalDateTime lockedUntil;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
