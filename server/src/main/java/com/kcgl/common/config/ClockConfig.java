package com.kcgl.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 全链路 Asia/Tokyo 时钟（docs/01 7.8）：业务代码一律注入本 bean 取「现在」，
 * 禁止裸 LocalDateTime.now()/Instant.now()——CI（UTC）与开发机（+08）行为一致的前提。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Tokyo"));
    }
}
