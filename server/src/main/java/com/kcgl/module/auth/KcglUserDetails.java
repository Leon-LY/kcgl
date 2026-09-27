package com.kcgl.module.auth;

import com.kcgl.module.user.SysUserEntity;
import com.kcgl.module.user.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 认证主体：包装 sys_user 行。
 * 账号锁（DB locked_until）在构造时按 JST 现值判定——DaoAuthenticationProvider 的
 * 前置检查先于密码校验执行，锁定期内即使密码正确也抛 LockedException → 423。
 */
public class KcglUserDetails implements UserDetails {

    private final Long userId;
    private final String username;
    private final String passwordHash;
    private final String displayName;
    private final UserRole role;
    private final String locale;
    private final boolean mustChangePwd;
    private final boolean accountNonLocked;
    private final boolean enabled;

    private KcglUserDetails(SysUserEntity e, Clock clock) {
        this.userId = e.getId();
        this.username = e.getUsername();
        this.passwordHash = e.getPasswordHash();
        this.displayName = e.getDisplayName();
        this.role = UserRole.of(e.getRole());
        this.locale = e.getLocale();
        this.mustChangePwd = e.getMustChangePwd() != null && e.getMustChangePwd() == 1;
        this.accountNonLocked = e.getLockedUntil() == null
                || e.getLockedUntil().isBefore(LocalDateTime.now(clock));
        this.enabled = e.getEnabled() != null && e.getEnabled() == 1;
    }

    public static KcglUserDetails of(SysUserEntity entity, Clock clock) {
        return new KcglUserDetails(entity, clock);
    }

    public Long getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserRole getRole() {
        return role;
    }

    public String getLocale() {
        return locale;
    }

    public boolean isMustChangePwd() {
        return mustChangePwd;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
