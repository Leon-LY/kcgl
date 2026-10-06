package com.kcgl.common.security;

import tools.jackson.databind.ObjectMapper;
import com.kcgl.common.web.ApiResponse;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.auth.LoginJsonHandlers;
import com.kcgl.module.auth.LoginLockService;
import com.kcgl.module.auth.LoginThrottleFilter;
import com.kcgl.module.user.SysUserMapper;
import com.kcgl.module.user.UserRole;
import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.session.SessionInformationExpiredEvent;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;

import java.io.IOException;
import java.time.Clock;
import java.util.HashSet;

/**
 * Session 认证 + RBAC 骨架（docs/01 八节）：
 * - formLogin 复用框架过滤器链（/api/auth/login 表单编码），成功/失败均为 JSON——不自研认证过滤器
 * - CSRF 关闭，SameSite=Strict + OriginCheckFilter 双保险
 * - 会话 12h 滑动（application.yml）、同账号 8 并发（超出踢最旧）、登录成功 changeSessionId 防固定
 * - 首版 URL 级 RBAC：/api/users/** 仅管理员（端点矩阵随模块落地逐条补齐）
 * Security 7：SessionRegistry 已迁至 org.springframework.security.core.session。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * CSRF 双保险之二（B2）：白名单留空时本层是**全拒**而非「仅 SameSite」——同源浏览器的
     * 登录/写操作必带 Origin，届时 403 一片而原因不显。启动期把这条说清楚，省掉一轮排查。
     */
    private static OriginCheckFilter originCheckFilter(SecurityProperties properties, ObjectMapper objectMapper) {
        if (properties.allowedOrigins().isEmpty()) {
            log.warn("kcgl.security.allowed-origins 为空：Origin 校验处于全拒模式，"
                    + "带 Origin 头的浏览器写请求（含登录）将返回 403；请在部署 .env 配置实际访问源");
        }
        return new OriginCheckFilter(new HashSet<>(properties.allowedOrigins()), objectMapper);
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** 会话生命周期事件 → SessionRegistry，maximumSessions(8) 依赖此 bean。 */
    @Bean
    public static HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            LoginJsonHandlers handlers,
            LoginLockService lockService,
            SecurityProperties properties,
            ObjectMapper objectMapper,
            SessionRegistry sessionRegistry,
            SysUserMapper userMapper,
            Clock clock) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // SSE 长连接完成后的 ASYNC 派发会重过滤链：容器内部派发不可伪造、
                        // 初次请求已过完整认证授权——放行，否则对已提交响应写 401 必刷 ERROR
                        // （会话被踢/过期关流的正常事件，D-045 E 同族治理）
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/logout").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // 图片直出（docs/01 7.5 A17）：URL 内嵌 128-bit UUID 不可枚举
                        .requestMatchers("/img/**").permitAll()
                        .requestMatchers("/api/users/**").hasRole(UserRole.ADMIN.name())
                        // 告警与错误上报查询仅管理员；client-errors 的 POST 走 anyRequest（登录用户均可）
                        .requestMatchers(HttpMethod.GET, "/api/client-errors").hasRole(UserRole.ADMIN.name())
                        .requestMatchers(HttpMethod.GET, "/api/alerts").hasRole(UserRole.ADMIN.name())
                        .requestMatchers(HttpMethod.PATCH, "/api/alerts/*/read").hasRole(UserRole.ADMIN.name())
                        .anyRequest().authenticated())
                .addFilterAfter(new AccountStatusFilter(userMapper, clock, objectMapper),
                        SecurityContextHolderFilter.class)
                .addFilterBefore(originCheckFilter(properties, objectMapper),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new LoginThrottleFilter(lockService, objectMapper),
                        UsernamePasswordAuthenticationFilter.class)
                .formLogin(form -> form
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler(handlers::onSuccess)
                        .failureHandler(handlers::onFailure))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(handlers::onLogout))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                // 关掉 RequestCache（D-081）：它的唯一用途是「401 时把原请求存进 session，
                // 登录后 302 跳回」——本应用是 JSON SPA + 自定义 AuthenticationEntryPoint，
                // 永远返回 401 JSON 不重定向，这条链一次也用不上。代价却极重：
                // HttpSessionRequestCache.saveRequest() 会 request.getSession(true)，
                // 于是**每个未认证请求都凭空建一个 HTTP 会话**且永不复用（客户端不接
                // Cookie 时），约 3.7KB/次。实测：2 万次未认证请求 → Tomcat 活跃会话
                // 恰好 +20000；堆涨到 ~40 万会话即 OOM（本地预演把 app 打到 44 次
                // OutOfMemoryError）。任何不带 Cookie 的客户端（爬虫/拨测/被剥 Cookie
                // 的代理）都能据此打满内存——典型的可用性放大面。
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId())
                        .maximumSessions(8)
                        .sessionRegistry(sessionRegistry)
                        .expiredSessionStrategy(jsonExpiredStrategy(objectMapper)))
                .headers(headers -> headers.frameOptions(frame -> frame.deny()));
        return http.build();
    }

    /** 并发超限被踢的旧会话：下一次请求返回 401 JSON（默认策略是 302 跳 /login，SPA 不适用）。 */
    private static SessionInformationExpiredStrategy jsonExpiredStrategy(ObjectMapper objectMapper) {
        return new SessionInformationExpiredStrategy() {
            @Override
            public void onExpiredSessionDetected(SessionInformationExpiredEvent event) throws IOException {
                jakarta.servlet.http.HttpServletResponse response = event.getResponse();
                // SSE 长连接的 ASYNC 完成派发会再过本策略：流已用 getOutputStream 且已提交，
                // 再 getWriter 必抛 IllegalStateException——正常踢人事件不该刷 ERROR 污染诊断导出
                if (response.isCommitted()) {
                    return;
                }
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.setCharacterEncoding("UTF-8");
                objectMapper.writeValue(response.getWriter(),
                        ApiResponse.error(ErrorCode.UNAUTHENTICATED));
            }
        };
    }
}
