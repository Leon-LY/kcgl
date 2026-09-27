package com.kcgl.module.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kcgl.common.audit.AuditRecorder;
import com.kcgl.common.util.PasswordGenerator;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.common.web.PageResponse;
import com.kcgl.module.user.dto.CreateUserRequest;
import com.kcgl.module.user.dto.PasswordResetResponse;
import com.kcgl.module.user.dto.UpdateUserRequest;
import com.kcgl.module.user.dto.UserResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 账号管理（仅管理员可达，URL 级 RBAC 已在 SecurityConfig 锁死 /api/users/**）。
 * - 用户只停用不删除（流水与审计的操作人快照依赖行恒存）
 * - unlock 必须用 UpdateWrapper 显式 set null——updateById 的默认策略忽略 null 字段，
 *   locked_until 清不掉（MyBatis-Plus 经典坑）
 * - 写方法 @Transactional：业务与审计同事务（审计失败即回滚，绝不丢审计——AuditRecorder 契约）
 */
@Service
public class UserService {

    private final SysUserMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final AuditRecorder auditRecorder;

    public UserService(SysUserMapper mapper, PasswordEncoder passwordEncoder, Clock clock,
            AuditRecorder auditRecorder) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.auditRecorder = auditRecorder;
    }

    public PageResponse<UserResponse> page(int page, int size) {
        Page<SysUserEntity> result = mapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<SysUserEntity>().orderByAsc(SysUserEntity::getId));
        return PageResponse.of(result.getRecords().stream().map(e -> UserResponse.from(e, clock)).toList(),
                result.getTotal(), page, size);
    }

    @Transactional
    public UserResponse create(CreateUserRequest req) {
        if (mapper.selectCount(new LambdaQueryWrapper<SysUserEntity>()
                .eq(SysUserEntity::getUsername, req.username())) > 0) {
            throw new BizException(ErrorCode.USER_EXISTS);
        }
        SysUserEntity entity = new SysUserEntity();
        entity.setUsername(req.username());
        entity.setPasswordHash(passwordEncoder.encode(req.password()));
        entity.setDisplayName(req.displayName());
        entity.setRole(req.role());
        entity.setLocale(req.locale());
        entity.setEnabled(1);
        entity.setMustChangePwd(1);
        entity.setFailedAttempts(0);
        entity.setCreatedAt(LocalDateTime.now(clock));
        entity.setUpdatedAt(LocalDateTime.now(clock));
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 预检与插入之间的并发竞态兜底（uk_username）
            throw new BizException(ErrorCode.USER_EXISTS);
        }
        auditRecorder.record("USER_CREATE", "sys_user", entity.getId(),
                Map.of("username", req.username()));
        return UserResponse.from(entity, clock);
    }

    @Transactional
    public UserResponse update(Long id, UpdateUserRequest req) {
        SysUserEntity entity = requireUser(id);
        Map<String, Object> before = Map.of(
                "displayName", entity.getDisplayName(),
                "role", entity.getRole(),
                "locale", entity.getLocale());
        entity.setDisplayName(req.displayName());
        entity.setRole(req.role());
        entity.setLocale(req.locale());
        entity.setUpdatedAt(LocalDateTime.now(clock));
        mapper.updateById(entity);
        auditRecorder.record("USER_UPDATE", "sys_user", id, Map.of(
                "username", entity.getUsername(),
                "before", before,
                "after", Map.of("displayName", req.displayName(), "role", req.role(), "locale", req.locale())));
        return UserResponse.from(entity, clock);
    }

    @Transactional
    public UserResponse updateStatus(Long id, int enabled, Long operatorId) {
        // 自锁护栏：唯一管理员停用自己后将无人能恢复（bootstrap 仅空库建号），拒绝
        if (enabled == 0 && id.equals(operatorId)) {
            throw new BizException(ErrorCode.VALIDATION, "自分自身を無効にすることはできません");
        }
        SysUserEntity entity = requireUser(id);
        entity.setEnabled(enabled);
        entity.setUpdatedAt(LocalDateTime.now(clock));
        mapper.updateById(entity);
        auditRecorder.record("USER_STATUS", "sys_user", id, Map.of(
                "username", entity.getUsername(), "enabled", enabled));
        return UserResponse.from(entity, clock);
    }

    /** 手动解锁：清 locked_until + failed_attempts，立即恢复可登录（D-022）。 */
    @Transactional
    public UserResponse unlock(Long id) {
        SysUserEntity entity = requireUser(id);
        mapper.update(null, new LambdaUpdateWrapper<SysUserEntity>()
                .eq(SysUserEntity::getId, id)
                .set(SysUserEntity::getLockedUntil, null)
                .set(SysUserEntity::getFailedAttempts, 0)
                .set(SysUserEntity::getUpdatedAt, LocalDateTime.now(clock)));
        auditRecorder.record("USER_UNLOCK", "sys_user", id, Map.of("username", entity.getUsername()));
        return UserResponse.from(requireUser(id), clock);
    }

    /** 管理员重置密码：一次性初始密码 + 强制首登改密；旧密码立即失效。审计行不含任何密码材料。 */
    @Transactional
    public PasswordResetResponse resetPassword(Long id) {
        SysUserEntity entity = requireUser(id);
        String initialPassword = PasswordGenerator.generate();
        entity.setPasswordHash(passwordEncoder.encode(initialPassword));
        entity.setMustChangePwd(1);
        entity.setUpdatedAt(LocalDateTime.now(clock));
        mapper.updateById(entity);
        auditRecorder.record("USER_PASSWORD_RESET", "sys_user", id, Map.of("username", entity.getUsername()));
        return new PasswordResetResponse(initialPassword);
    }

    private SysUserEntity requireUser(Long id) {
        SysUserEntity entity = mapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return entity;
    }
}
