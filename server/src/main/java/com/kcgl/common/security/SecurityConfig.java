package com.kcgl.common.security;

import tools.jackson.databind.ObjectMapper;
import com.kcgl.common.web.ApiResponse;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.auth.LoginJsonHandlers;
import com.kcgl.module.auth.LoginLockService;
import com.kcgl.module.auth.LoginThrottleFilter;
import com.kcgl.module.user.SysUserMapper;
import com.kcgl.module.user.UserRole;
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

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
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
                        .requestMatchers("/api/auth/login", "/api/auth/logout").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/api/users/**").hasRole(UserRole.ADMIN.name())
                        // 告警与错误上报查询仅管理员；client-errors 的 POST 走 anyRequest（登录用户均可）
                        .requestMatchers(HttpMethod.GET, "/api/client-errors").hasRole(UserRole.ADMIN.name())
                        .requestMatchers(HttpMethod.GET, "/api/alerts").hasRole(UserRole.ADMIN.name())
                        .requestMatchers(HttpMethod.PATCH, "/api/alerts/*/read").hasRole(UserRole.ADMIN.name())
                        .anyRequest().authenticated())
                .addFilterAfter(new AccountStatusFilter(userMapper, clock, objectMapper),
                        SecurityContextHolderFilter.class)
                .addFilterBefore(new OriginCheckFilter(
                                new HashSet<>(properties.allowedOrigins()), objectMapper),
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
                event.getResponse().setStatus(401);
                event.getResponse().setContentType("application/json;charset=UTF-8");
                event.getResponse().setCharacterEncoding("UTF-8");
                objectMapper.writeValue(event.getResponse().getWriter(),
                        ApiResponse.error(ErrorCode.UNAUTHENTICATED));
            }
        };
    }
}
