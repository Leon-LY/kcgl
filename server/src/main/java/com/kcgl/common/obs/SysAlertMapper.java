package com.kcgl.common.obs;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 告警表：uk_dedup 去重写走原生 upsert（同键告警刷新为最新开启态，不堆积重复行）。 */
@Mapper
public interface SysAlertMapper extends BaseMapper<SysAlertEntity> {

    @Insert("""
            INSERT INTO sys_alert(type, dedup_key, level, message, payload, status, created_at)
            VALUES (#{type}, #{dedupKey}, #{level}, #{message}, #{payload}, 0, #{now})
            ON DUPLICATE KEY UPDATE
              level = VALUES(level), message = VALUES(message), payload = VALUES(payload),
              status = 0, read_by = NULL, read_at = NULL, created_at = VALUES(created_at)
            """)
    int upsertOpen(@Param("type") String type, @Param("dedupKey") String dedupKey,
            @Param("level") int level, @Param("message") String message,
            @Param("payload") String payload, @Param("now") java.time.LocalDateTime now);
}
