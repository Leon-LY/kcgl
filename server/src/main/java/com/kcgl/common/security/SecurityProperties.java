package com.kcgl.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 安全相关部署配置。allowed-origins 例：https://kcgl.example.com（多个逗号分隔）。
 *
 * <p><b>留空的实际语义（B2，原 javadoc 写错）</b>：白名单是空集时，任何**带非空 Origin 头**的
 * 非幂等请求一律 403——不是「退化为仅 SameSite=Strict」，而是**全拒**（fail-closed）。
 * 同源部署下浏览器的登录与全部写操作必带 Origin，故留空 = 这些请求全部不可用。
 * 部署面因此把它做成必填（deploy/docker-compose.yml 的 {@code :?} 占位符直接拦住无值启动），
 * 本地预演则由根 docker-compose.yml 给默认值。留空只剩两种合理场景：非浏览器客户端调用
 * （无 Origin 头 → 放行），或把 SPA 与 API 放在不同源却忘了配白名单——后者会在启动日志里
 * 看到一条 WARN，登录时报 403（见 SecurityConfig）。
 */
@ConfigurationProperties(prefix = "kcgl.security")
public record SecurityProperties(List<String> allowedOrigins) {

    public SecurityProperties {
        if (allowedOrigins == null) {
            allowedOrigins = List.of();
        }
    }
}
