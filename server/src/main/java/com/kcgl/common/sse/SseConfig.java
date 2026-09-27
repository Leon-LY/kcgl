package com.kcgl.common.sse;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 心跳调度开关：SseHub#heartbeat 的 @Scheduled(20s) 驻留验活依赖此处启用。
 * 独立配置类而非挂在应用主类——调度属于 SSE 子域，与其生命周期同进退。
 */
@Configuration
@EnableScheduling
public class SseConfig {
}
