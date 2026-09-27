package com.kcgl.common.sse;

import com.kcgl.module.user.SysUserEntity;
import com.kcgl.module.user.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SseHub 单元测试（M3-③，docs/01 7.6）：发送纪律与生命周期闭合的并发行为验证。
 * 载荷 JSON 形状/真实线上字节由 SseIntegrationTest 断言——此处 mock emitter 只关心
 * 「发了几次、失败后是否断连、哪些连接被关闭」。
 */
class SseHubTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-28T00:00:00Z"), ZoneId.of("Asia/Tokyo"));

    SessionRegistry sessionRegistry;
    SysUserMapper userMapper;
    SseHub hub;

    @BeforeEach
    void setUp() {
        sessionRegistry = mock(SessionRegistry.class);
        userMapper = mock(SysUserMapper.class);
        // 发送超时压到 200ms：慢客户端断连用例无需等生产 5s；emitter 全部注入 mock
        hub = new SseHub(sessionRegistry, userMapper, CLOCK, 200) {
            @Override
            SseEmitter newEmitter() {
                return mock(SseEmitter.class);
            }
        };
    }

    @AfterEach
    void tearDown() {
        hub.shutdown();
    }

    @Test
    void register_sendsHelloAndCountsConnection() throws IOException {
        SseEmitter emitter = hub.register(1L, "s1");

        // HELLO 在 register 返回前同步送达（发送经 future.get 等待完成）
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(hub.connectionCount()).isEqualTo(1);
    }

    @Test
    void emitterCompletionCallback_removesEntry() {
        SseEmitter emitter = hub.register(1L, "s1");

        ArgumentCaptor<Runnable> onComplete = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onCompletion(onComplete.capture());

        // Spring 回调（连接结束）→ 条目出清，不泄漏
        onComplete.getValue().run();
        assertThat(hub.connectionCount()).isZero();
    }

    @Test
    void broadcast_fansOutAndDisconnectsBrokenEmitter() throws IOException {
        SseEmitter healthy = hub.register(1L, "s1");
        SseEmitter broken = hub.register(2L, "s2");
        // 注册后再让坏连接抛错——HELLO 仍要送达，坏的是后续广播
        doThrow(new IOException("broken pipe")).when(broken)
                .send(any(SseEmitter.SseEventBuilder.class));

        hub.broadcast(SyncEvent.TYPE_ITEM, "HTK9-A1X", 1L);

        // healthy：HELLO + 广播共两次；broken：发送失败 → 断连出清
        verify(healthy, timeout(1000).times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(broken, timeout(1000)).complete();
        assertThat(hub.connectionCount()).isEqualTo(1);
    }

    @Test
    void slowEmitter_sendTimeoutDisconnects() throws IOException {
        SseEmitter slow = hub.register(1L, "s1");
        doAnswer(invocation -> {
            try {
                Thread.sleep(1000); // 远超 200ms 发送超时（模拟 TCP 背压）
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(slow).send(any(SseEmitter.SseEventBuilder.class));

        hub.broadcast(SyncEvent.TYPE_ITEM, "HTK9-A1X", null);

        // 超时即断连——慢客户端不得无限占用发送预算
        verify(slow, timeout(3000)).complete();
        assertThat(hub.connectionCount()).isZero();
    }

    @Test
    void closeSession_andSessionDestroyedEvent_closeOnlyMatchingConnections() {
        SseEmitter a = hub.register(1L, "s1");
        SseEmitter b = hub.register(1L, "s2");

        hub.closeSession("s1");
        verify(a, timeout(1000)).complete();
        // 单线程 FIFO：a 关闭后本批任务已执行完毕，s2 的连接不受影响
        verify(b, never()).complete();
        assertThat(hub.connectionCount()).isEqualTo(1);

        // 登出/12h 超时 → HttpSessionDestroyedEvent → 同一关闭路径
        hub.onApplicationEvent(new HttpSessionDestroyedEvent(new MockHttpSession(null, "s2")));
        verify(b, timeout(1000)).complete();
        assertThat(hub.connectionCount()).isZero();
    }

    @Test
    void heartbeat_revalidates_closesExpiredAndDead_pingsHealthy() throws IOException {
        // s1：并发超限被踢（expireNow 只标记不发事件——请求路径由 ConcurrentSessionFilter 兜，
        // SSE 长连接只能靠心跳补杀）
        SseEmitter expired = hub.register(1L, "s1");
        SessionInformation expiredInfo = liveSession("s1");
        expiredInfo.expireNow();
        when(sessionRegistry.getSessionInformation("s1")).thenReturn(expiredInfo);
        // s2：会话活着但账号已停用（长连接无后续请求，AccountStatusFilter 永无机会执行）
        SseEmitter disabled = hub.register(2L, "s2");
        when(sessionRegistry.getSessionInformation("s2")).thenReturn(liveSession("s2"));
        // s3：健康连接
        SseEmitter healthy = hub.register(3L, "s3");
        when(sessionRegistry.getSessionInformation("s3")).thenReturn(liveSession("s3"));
        when(userMapper.selectByIds(anyCollection()))
                .thenReturn(List.of(user(2L, 0), user(3L, 1)));

        hub.heartbeat();

        verify(expired, timeout(1000)).complete();
        verify(disabled, timeout(1000)).complete();
        // 健康连接收到注释 ping（HELLO + ping 共两次），且绝不被误关
        verify(healthy, timeout(1000).times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(healthy, never()).complete();
        assertThat(hub.connectionCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------- 夹具

    private SessionInformation liveSession(String sessionId) {
        return new SessionInformation("principal", sessionId, new Date());
    }

    private SysUserEntity user(long id, int enabled) {
        SysUserEntity entity = new SysUserEntity();
        entity.setId(id);
        entity.setEnabled(enabled);
        return entity;
    }
}
