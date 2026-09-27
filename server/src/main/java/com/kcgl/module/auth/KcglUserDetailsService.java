package com.kcgl.module.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kcgl.module.user.SysUserEntity;
import com.kcgl.module.user.SysUserMapper;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * 账号查询适配器。未知用户名先跑一次 dummy BCrypt 再抛异常——
 * 抹平「用户不存在（快返回）vs 密码错误（慢返回）」的时序差，防用户枚举（docs/01 八节）。
 * 响应内容层面由失败处理器统一为同一 JSON，双层防枚举。
 */
@Service
public class KcglUserDetailsService implements UserDetailsService {

    static final String DUMMY_RAW_PASSWORD = "timing-equalizer";

    private final SysUserMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public KcglUserDetailsService(SysUserMapper mapper, PasswordEncoder passwordEncoder, Clock clock) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    public KcglUserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUserEntity entity = mapper.selectOne(
                Wrappers.<SysUserEntity>lambdaQuery().eq(SysUserEntity::getUsername, username));
        if (entity == null) {
            passwordEncoder.encode(DUMMY_RAW_PASSWORD);
            throw new UsernameNotFoundException("ユーザー名またはパスワードが正しくありません");
        }
        return KcglUserDetails.of(entity, clock);
    }
}
