package com.kcgl.common.audit;

import com.kcgl.module.auth.KcglUserDetails;
import com.kcgl.module.log.OperationLogEntity;
import com.kcgl.module.log.OperationLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 审计记录器（docs/01 八节）：业务事务内同步调用——调用方方法必须 @Transactional，
 * 审计与业务同生共死（业务回滚则审计不留痕；审计写失败则业务一并回滚，绝不丢审计）。
 *
 * 设计取舍（D-026）：显式调用而非 @Audited AOP——前后值 diff 在方法内部，
 * 切面拿不到，反射/SpEL 提取的复杂度不抵声明式的收益。
 *
 * 纪律：detail 与整行日志严禁出现密码明文/哈希（契约测试断言）；
 * operator 取会话快照（id+username），与业务表操作人列同源。
 */
@Component
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);
    private static final int UA_MAX = 255;
    private static final int IP_MAX = 45;

    private final OperationLogMapper mapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditRecorder(OperationLogMapper mapper, ObjectMapper objectMapper, Clock clock) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 记一条审计。detail 为 null 时列存 NULL；调用方须在 @Transactional 方法内。
     *
     * @throws IllegalStateException 无认证上下文（审计必须知道操作人；后台任务写 sys_alert 而非本表）
     */
    public void record(String action, String entityType, Long entityId, Map<String, Object> detail) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof KcglUserDetails operator)) {
            throw new IllegalStateException("审计记录缺少认证上下文: action=" + action);
        }
        insert(action, entityType, entityId, detail, operator.getUserId(), operator.getUsername(),
                currentIp(), currentUa());
    }

    /**
     * 后台线程版（无会话/请求上下文）：异步任务在提交线程捕获操作人显式传参
     * （docs/01 5.3 operator 快照——异步线程不读 SecurityContext）；ip/ua 留 NULL。
     */
    public void record(String action, String entityType, Long entityId, Map<String, Object> detail,
            long operatorId, String operatorName) {
        insert(action, entityType, entityId, detail, operatorId, operatorName, null, null);
    }

    private void insert(String action, String entityType, Long entityId, Map<String, Object> detail,
            long operatorId, String operatorName, String ip, String ua) {
        OperationLogEntity entry = new OperationLogEntity();
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setDetail(detail == null ? null : objectMapper.writeValueAsString(detail));
        entry.setOperatorId(operatorId);
        entry.setOperatorName(operatorName);
        entry.setIp(ip);
        entry.setUa(ua);
        entry.setCreatedAt(LocalDateTime.now(clock));
        mapper.insert(entry);
        log.debug("审计 action={} entity={}/{} operator={}", action, entityType, entityId, operatorName);
    }

    /**
     * 客户端 IP：统一取 {@code getRemoteAddr()}——反代拓扑下由 Tomcat RemoteIpValve
     * （{@code server.forward-headers-strategy=native}）从 X-Forwarded-For 自右向左跳过可信代理
     * 解析而来，已是真实客户端地址。
     *
     * <p>**禁止直读 X-Forwarded-For 首值**：nginx 用 {@code $proxy_add_x_forwarded_for}
     * 拼出「客户端自带值 + , + 真实地址」，首值完全由客户端控制——直读会把审计 IP 栽赃成任意
     * 地址（伪造操作来源、污染取证）。
     */
    private String currentIp() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        String ip = attrs.getRequest().getRemoteAddr();
        return ip != null && ip.length() > IP_MAX ? ip.substring(0, IP_MAX) : ip;
    }

    private String currentUa() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        String ua = attrs.getRequest().getHeader("User-Agent");
        return ua != null && ua.length() > UA_MAX ? ua.substring(0, UA_MAX) : ua;
    }
}
