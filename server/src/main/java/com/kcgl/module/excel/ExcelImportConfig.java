package com.kcgl.module.excel;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Excel 导入专用执行器（D-058 B）：core=max=1 串行——行级逐条事务且共享管理号
 * 计数器桶，串行化把导入与在线录入的锁竞争窗口压到单行粒度；有界队列（2）+
 * Abort——上传洪峰显式 429 拒绝而非无界堆积（虚拟线程默认执行器无界且池参数
 * 失效，故显式平台线程池）。与雅虎管线互不占用（各自独立执行器）。
 */
@Configuration
public class ExcelImportConfig {

    @Bean
    ThreadPoolTaskExecutor excelImportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2);
        executor.setThreadNamePrefix("excel-import-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
