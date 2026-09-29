package com.kcgl.common.obs;

import ch.qos.logback.classic.Logger;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 环形缓冲挂载（M5-④）：编程式把 {@link RingBufferLogAppender} 挂到 root logger。
 * 不引入 logback-spring.xml——自定义配置文件会接管 Boot 的控制台装配，
 * 破坏 application.yml 的 logging.structured.format.console=ecs（单行 JSON）；
 * 编程式追加 appender 与默认链路互不干扰。
 */
@Component
public class RingBufferLogAttachListener {

    @PostConstruct
    void attach() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        RingBufferLogAppender appender = new RingBufferLogAppender();
        appender.setContext(root.getLoggerContext());
        appender.setName("KCGL_RING_BUFFER");
        appender.start();
        root.addAppender(appender);
    }
}
