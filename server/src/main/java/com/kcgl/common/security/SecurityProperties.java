package com.kcgl.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 安全相关部署配置。allowed-origins 例：https://kcgl.example.com（多个逗号分隔）。
 * 留空时 Origin 校验层退化为仅 SameSite=Strict 防线（本地开发/测试默认）。
 */
@ConfigurationProperties(prefix = "kcgl.security")
public record SecurityProperties(List<String> allowedOrigins) {

    public SecurityProperties {
        if (allowedOrigins == null) {
            allowedOrigins = List.of();
        }
    }
}
