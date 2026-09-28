package com.kcgl.module.yahoo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 导入专用执行器（docs/01 7.4）：core=max=1 串行——批次按提交顺序处理；
 * 有界队列（4）+ Abort——上传洪峰显式拒绝而非无界堆积（虚拟线程默认执行器
 * 无界且池参数失效，故显式平台线程池）。
 */
@Configuration
public class YahooImportConfig {

    @Bean
    ThreadPoolTaskExecutor yahooImportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("yahoo-import-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
