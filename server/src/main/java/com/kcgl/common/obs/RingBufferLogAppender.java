package com.kcgl.common.obs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.MDC;

import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 内存环形缓冲（docs/01 9.3）：保留最近 {@link #CAPACITY} 条 ERROR/WARN 供诊断导出。
 * traceId 在 append 线程现场从 MDC 取（异步线程经 TaskDecorator 传播，docs/01 9.3），
 * 异常取类名+消息（完整栈不进内存，文件日志才是完整记录）。
 *
 * <p>缓冲为 JVM 单例静态（logback appender 由 {@link RingBufferLogAttachListener}
 * 编程式挂到 root——不动 logback-spring.xml，保住 Boot 默认 ECS 控制台链路）。
 * 只为诊断导出生，不做实时消费；越界丢弃最旧（ArrayDeque 头部）。
 */
public class RingBufferLogAppender extends AppenderBase<ILoggingEvent> {

    /** docs/01 9.3 固定容量。 */
    static final int CAPACITY = 500;

    private static final Deque<String> LINES = new ArrayDeque<>();
    /** 包级可见：单测在锁内做 clear→追加→断言，屏蔽同 JVM 存活上下文的异步 WARN 噪声。 */
    static final Object LOCK = new Object();
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    @Override
    protected void append(ILoggingEvent event) {
        if (!event.getLevel().isGreaterOrEqual(Level.WARN)) {
            return;
        }
        String line = format(event);
        synchronized (LOCK) {
            while (LINES.size() >= CAPACITY) {
                LINES.pollFirst();
            }
            LINES.addLast(line);
        }
    }

    private static String format(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(event.getTimeStamp()), JST))
                .append(' ').append(event.getLevel())
                .append(" [").append(MDC.get(TraceIdFilter.MDC_TRACE_ID)).append("] ")
                .append(event.getLoggerName()).append(" - ")
                .append(event.getFormattedMessage());
        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            sb.append(" | ").append(throwable.getClassName())
                    .append(": ").append(throwable.getMessage());
        }
        return sb.toString();
    }

    /** 诊断导出快照：最新在后；只读副本（导出期间新日志不进快照）。 */
    public static List<String> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(LINES);
        }
    }

    /** 测试与单测隔离用。 */
    static void clear() {
        synchronized (LOCK) {
            LINES.clear();
        }
    }
}
