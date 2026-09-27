package com.kcgl.module.log;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 操作日志：继承 BaseMapper 仅为 insert 与只读查询；改删在 API 层不存在、DB 层被 GRANT 拒绝。 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLogEntity> {
}
