package com.kcgl.module.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 账号 Mapper：M1 仅用 BaseMapper 通用方法（selectOne/selectById/insert/update）。
 * 复杂 SQL 一律 XML（docs/01 四节），此处不出现手写 SQL。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUserEntity> {
}
