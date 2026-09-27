package com.kcgl.common.sse;

import com.kcgl.common.security.AccountStatuses;
import com.kcgl.module.user.SysUserEntity;
import com.kcgl.module.user.SysUserMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * SSE 集线器（docs/01 7.6 唯一定义）：userId+sessionId → emitter 双键登记。
 *
 * <p>生命周期闭合（「已登出设备持续接收全店业务事件」的唯一防线）：
 * 登出/12h 超时 → {@link HttpSessionDestroyedEvent} 即时关闭该会话全部连接；
 * 并发超限被踢（expireNow 不发 destroy 事件）与账号停用（长连接无后续请求，
 * AccountStatusFilter 永无机会执行）由心跳周期内补杀——最迟 20s。
 *
 * <p>发送纪律：per-emitter 监视器锁（synchronized）串行化——SseEmitter 非线程安全，
 * 而临界区仅一次发送、无 tryLock/Condition 之需，监视器即足够（虚拟线程 pinning
 * 已评估：载具占用上限=发送超时 5s，≤10 连接规模无感）；每次发送 5s 超时
 * （慢客户端 TCP 背压不得阻塞共享发送循环）；广播/心跳全部经单线程 FIFO 执行器
 * （事件全序，逐条阻塞上限 5s 后断连自愈）。
 */
@Component
public class SseHub implements ApplicationListener<HttpSessionDestroyedEvent> {

    /** 心跳周期：注释行防代理空闲断连（20s &lt; 常见代理 60s）。 */
    static final long HEARTBEAT_MS = 20_000;

    /** 单次发送超时（docs/01 7.6：超时即断连）。 */
    static final long SEND_TIMEOUT_MS = 5_000;

    /** 一条已登记连接。 */
    private record Entry(SseEmitter emitter, long userId, String sessionId) {
    }

    private final Map<SseEmitter, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 广播/心跳共用单线程 FIFO：事件全序（seq 顺序与到达顺序一致）。 */
    private final ExecutorService pipeline = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sse-pipeline");
        t.setDaemon(true);
        return t;
    });

    /** 发送线程池：虚拟线程每任务——pipeline 线程对其 get(timeout) 实现 5s 上限。 */
    private final ExecutorService sendPool = Executors.newVirtualThreadPerTaskExecutor();

    private final long sendTimeoutMs;
    private final SessionRegistry sessionRegistry;
    private final SysUserMapper userMapper;
    private final Clock clock;

    @Autowired
    public SseHub(SessionRegistry sessionRegistry, SysUserMapper userMapper, Clock clock) {
        this(sessionRegistry, userMapper, clock, SEND_TIMEOUT_MS);
    }

    SseHub(SessionRegistry sessionRegistry, SysUserMapper userMapper, Clock clock, long sendTimeoutMs) {
        this.sessionRegistry = sessionRegistry;
        this.userMapper = userMapper;
        this.clock = clock;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    /** 登记一条连接（控制器调用）：初始 HELLO 即时下发（ResponseBodyEmitter 缓冲早发）。 */
    public SseEmitter register(long userId, String sessionId) {
        SseEmitter emitter = newEmitter();
        Entry entry = new Entry(emitter, userId, sessionId);
        entries.put(emitter, entry);
        emitter.onCompletion(() -> entries.remove(emitter));
        emitter.onTimeout(() -> entries.remove(emitter));
        emitter.onError(ignored -> entries.remove(emitter));
        try {
            send(entry, SseEmitter.event().data(
                    new SyncEvent(seq.get(), SyncEvent.TYPE_HELLO, null, null, LocalDateTime.now(clock))));
        } catch (SendFailure e) {
            entries.remove(emitter);
        }
        return emitter;
    }

    /**
     * 创建 emitter：无参构造=超时交给容器/MVC 默认（已配 request-timeout 12h）——
     * 心跳管活性之外的超期硬兜底，被切连接由 EventSource 原生重连自愈（新流+新 HELLO）。
     * 测试子类覆写以注入 mock（register 内部构造，无此接缝则不可测）。
     */
    SseEmitter newEmitter() {
        return new SseEmitter();
    }

    /** 全店广播（动作事务提交后调用）：所有在线连接收同一信封。 */
    public void broadcast(String type, String entity, Long operatorId) {
        SyncEvent event = new SyncEvent(seq.incrementAndGet(), type, entity, operatorId,
                LocalDateTime.now(clock));
        pipeline.execute(() -> {
            for (Entry entry : List.copyOf(entries.values())) {
                try {
                    send(entry, SseEmitter.event().data(event));
                } catch (SendFailure e) {
                    disconnect(entry);
                }
            }
        });
    }

    /**
     * 心跳（20s）：先验活——会话消失/被标过期（并发超限踢出无 destroy 事件）与
     * 账号停用/锁定（长连接无请求路径可拦，D-024 口径）补杀；幸存连接送注释 ping。
     */
    @Scheduled(fixedDelay = HEARTBEAT_MS)
    public void heartbeat() {
        pipeline.execute(() -> {
            for (Entry entry : List.copyOf(entries.values())) {
                SessionInformation info = sessionRegistry.getSessionInformation(entry.sessionId());
                if (info == null || info.isExpired()) {
                    disconnect(entry);
                }
            }
            closeDeadAccounts();
            for (Entry entry : List.copyOf(entries.values())) {
                try {
                    send(entry, SseEmitter.event().comment("ping"));
                } catch (SendFailure e) {
                    disconnect(entry);
                }
            }
        });
    }

    /** 会话销毁（登出/超时/容器回收）：关闭该会话全部连接。 */
    @Override
    public void onApplicationEvent(HttpSessionDestroyedEvent event) {
        closeSession(event.getSession().getId());
    }

    public void closeSession(String sessionId) {
        pipeline.execute(() -> {
            for (Entry entry : List.copyOf(entries.values())) {
                if (entry.sessionId().equals(sessionId)) {
                    disconnect(entry);
                }
            }
        });
    }

    /** 当前在线连接数（observability/测试）。 */
    public int connectionCount() {
        return entries.size();
    }

    @PreDestroy
    void shutdown() {
        pipeline.execute(() -> {
            for (Entry entry : List.copyOf(entries.values())) {
                disconnect(entry);
            }
        });
        pipeline.shutdownNow();
        sendPool.shutdownNow();
    }

    // ------------------------------------------------------------------ 内部

    /** 账号停用/锁定补杀：批量查用户表（≤10 用户规模，一次心跳一批查询无压力）。 */
    private void closeDeadAccounts() {
        Set<Long> userIds = entries.values().stream().map(Entry::userId).collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, SysUserEntity> users = userMapper.selectByIds(userIds).stream()
                .collect(Collectors.toMap(SysUserEntity::getId, Function.identity()));
        for (Entry entry : List.copyOf(entries.values())) {
            if (AccountStatuses.dead(users.get(entry.userId()), now)) {
                disconnect(entry);
            }
        }
    }

    /**
     * 单条发送：per-emitter 监视器锁 + 发送线程隔离 + 超时。
     * 失败统一折叠为 {@link SendFailure}（断连语义，调用方 disconnect）。
     */
    private void send(Entry entry, SseEmitter.SseEventBuilder payload) throws SendFailure {
        // return null 使任务成为 Callable：SendFailure（受检）可从虚拟线程抛出、经 future 聚合回传
        Future<?> future = sendPool.submit(() -> {
            synchronized (entry) {
                try {
                    entry.emitter().send(payload);
                } catch (IOException | IllegalStateException e) {
                    throw new SendFailure(e);
                }
            }
            return null;
        });
        try {
            future.get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new SendFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SendFailure(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new SendFailure(e.getCause());
        }
    }

    private void disconnect(Entry entry) {
        entries.remove(entry.emitter());
        try {
            entry.emitter().complete();
        } catch (IllegalStateException ignored) {
            // 已完成/已超时的 emitter 再 complete 属良性竞态
        }
    }

    /** 发送失败（IO/超时/中断）——统一断连语义。 */
    static final class SendFailure extends Exception {
        SendFailure(Throwable cause) {
            super(cause);
        }
    }
}
