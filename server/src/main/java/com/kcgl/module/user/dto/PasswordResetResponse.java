package com.kcgl.module.user.dto;

/**
 * 管理员重置密码的结果：初始密码仅在本次响应体中出现一次
 * （不落日志、不落库明文），由管理员转交用户后强制首登改密。
 */
public record PasswordResetResponse(String initialPassword) {
}
