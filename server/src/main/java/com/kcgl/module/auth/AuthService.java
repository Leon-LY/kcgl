package com.kcgl.module.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kcgl.common.audit.AuditRecorder;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.auth.dto.ChangePasswordRequest;
import com.kcgl.module.auth.dto.ChangeLocaleRequest;
import com.kcgl.module.user.SysUserEntity;
import com.kcgl.module.user.SysUserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 会话自助写操作：改密/改界面语言。@Transactional 保证业务与审计同事务
 * （审计失败即回滚，绝不丢审计——AuditRecorder 契约）。
 */
@Service
public class AuthService {

    private final SysUserMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final AuditRecorder auditRecorder;

    public AuthService(SysUserMapper mapper, PasswordEncoder passwordEncoder, Clock clock,
            AuditRecorder auditRecorder) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.auditRecorder = auditRecorder;
    }

    /** 审计行只记用户名——旧/新密码任何材料（明文、哈希）严禁入日志。 */
    @Transactional
    public void changePassword(KcglUserDetails user, ChangePasswordRequest request) {
        SysUserEntity entity = mapper.selectById(user.getUserId());
        if (entity == null || !passwordEncoder.matches(request.oldPassword(), entity.getPasswordHash())) {
            throw new BizException(ErrorCode.OLD_PASSWORD_MISMATCH);
        }
        if (request.newPassword().equals(request.oldPassword())) {
            throw new BizException(ErrorCode.PASSWORD_POLICY);
        }
        mapper.update(null, Wrappers.<SysUserEntity>lambdaUpdate()
                .eq(SysUserEntity::getId, user.getUserId())
                .set(SysUserEntity::getPasswordHash, passwordEncoder.encode(request.newPassword()))
                .set(SysUserEntity::getMustChangePwd, 0)
                .set(SysUserEntity::getUpdatedAt, LocalDateTime.now(clock)));
        auditRecorder.record("CHANGE_PASSWORD", "sys_user", user.getUserId(),
                Map.of("username", user.getUsername()));
    }

    @Transactional
    public void changeLocale(KcglUserDetails user, ChangeLocaleRequest request) {
        mapper.update(null, Wrappers.<SysUserEntity>lambdaUpdate()
                .eq(SysUserEntity::getId, user.getUserId())
                .set(SysUserEntity::getLocale, request.locale())
                .set(SysUserEntity::getUpdatedAt, LocalDateTime.now(clock)));
        auditRecorder.record("CHANGE_LOCALE", "sys_user", user.getUserId(),
                Map.of("username", user.getUsername(), "locale", request.locale()));
    }
}
