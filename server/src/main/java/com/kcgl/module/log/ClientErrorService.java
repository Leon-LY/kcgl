package com.kcgl.module.log;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.common.web.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 前端错误上报：登录会话内 + 每用户日上限 200（超限 429，防恶意灌库）+ 服务端截断
 * （错误上报路径宁可截断字段不可 400 拒收——丢错误比丢字段更糟）。
 */
@Service
public class ClientErrorService {

    static final int DAILY_LIMIT_PER_USER = 200;
    static final int MESSAGE_MAX = 1000;
    static final int STACK_MAX = 50000;
    static final int UA_MAX = 255;

    private final ClientErrorMapper mapper;
    private final Clock clock;

    public ClientErrorService(ClientErrorMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public void report(Long userId, String message, String stack, String route, String ua,
            String locale, String appVersion, String errorId, Integer queuePending,
            Integer queueOldestAgeSec) {
        LocalDateTime todayStart = LocalDateTime.now(clock).toLocalDate().atStartOfDay();
        Long todayCount = mapper.selectCount(new LambdaQueryWrapper<ClientErrorEntity>()
                .eq(ClientErrorEntity::getUserId, userId)
                .ge(ClientErrorEntity::getCreatedAt, todayStart));
        if (todayCount != null && todayCount >= DAILY_LIMIT_PER_USER) {
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
        ClientErrorEntity entity = new ClientErrorEntity();
        entity.setMessage(truncate(message, MESSAGE_MAX));
        entity.setStack(truncate(stack, STACK_MAX));
        entity.setRoute(truncate(route, 255));
        entity.setUa(truncate(ua, UA_MAX));
        entity.setLocale(truncate(locale, 8));
        entity.setAppVersion(truncate(appVersion, 32));
        entity.setUserId(userId);
        entity.setErrorId(truncate(errorId, 16));
        entity.setQueuePending(queuePending);
        entity.setQueueOldestAgeSec(queueOldestAgeSec);
        entity.setCreatedAt(LocalDateTime.now(clock));
        mapper.insert(entity);
    }

    public PageResponse<ClientErrorEntity> page(int page, int size) {
        Page<ClientErrorEntity> result = mapper.selectPage(new Page<>(Math.max(1, page), Math.min(100, size)),
                new LambdaQueryWrapper<ClientErrorEntity>().orderByDesc(ClientErrorEntity::getId));
        return PageResponse.of(result.getRecords(), result.getTotal(), page, size);
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
