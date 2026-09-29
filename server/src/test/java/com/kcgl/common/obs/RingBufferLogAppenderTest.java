package com.kcgl.common.obs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 环形缓冲单元测试（M5-④）：只留 WARN 以上、traceId 进行、
 * 异常取类名+消息、容量 500 越界丢最旧。静态缓冲 JVM 共享——全套件里
 * 存活的 Spring 上下文（Hikari 看门狗等）会并发往 root logger 写 WARN，
 * 故每例的 clear→追加→断言全程持 {@link RingBufferLogAppender#LOCK}
 * （同线程可重入；噪声线程阻塞到断言结束后由 @AfterEach clear 清场）。
 */
class RingBufferLogAppenderTest {

    private static final String TRACE_ID = "t-rbuf-001";

    private final RingBufferLogAppender appender = new RingBufferLogAppender();
    private final Logger logger = (Logger) LoggerFactory.getLogger("test.ring-buffer");

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false); // 全套件里 root 已挂监听器 appender——阻断上行避免双写
        logger.setLevel(Level.TRACE); // 门放最低：过滤责任全在 appender
        MDC.put(TraceIdFilter.MDC_TRACE_ID, TRACE_ID);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        RingBufferLogAppender.clear();
        MDC.clear();
    }

    @Test
    void keepsOnlyWarnAndAboveWithTraceIdAndThrowableSummary() {
        List<String> lines;
        List<String> afterExtra;
        synchronized (RingBufferLogAppender.LOCK) {
            RingBufferLogAppender.clear();
            logger.info("info 件は無視される");
            logger.warn("在庫同期が遅延しています");
            logger.error("CSV 取り込みに失敗", new IllegalStateException("boom"));

            lines = RingBufferLogAppender.snapshot();

            // 快照是只读副本：随后再写不影响已取快照
            logger.warn("追加の 1 件");
            afterExtra = RingBufferLogAppender.snapshot();
        }
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("WARN").contains("在庫同期が遅延しています");
        assertThat(lines.get(0)).contains("[" + TRACE_ID + "]"); // MDC 在 append 线程现场取
        assertThat(lines.get(1)).contains("ERROR").contains("CSV 取り込みに失敗");
        assertThat(lines.get(1)).contains("IllegalStateException").contains("boom");
        assertThat(lines).hasSize(2);
        assertThat(afterExtra).hasSize(3);
    }

    @Test
    void trimsOldestBeyondCapacity() {
        List<String> lines;
        synchronized (RingBufferLogAppender.LOCK) {
            RingBufferLogAppender.clear();
            for (int i = 0; i < RingBufferLogAppender.CAPACITY + 100; i++) {
                logger.error("スパイルト {}", i);
            }
            lines = RingBufferLogAppender.snapshot();
        }
        assertThat(lines).hasSize(RingBufferLogAppender.CAPACITY);
        assertThat(lines.getFirst()).contains("スパイルト 100"); // 最旧被丢
        assertThat(lines.getLast()).contains("スパイルト 599");
    }
}
