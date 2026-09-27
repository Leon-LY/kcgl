package com.kcgl.module.setting;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * 系统设置读写（docs/01 5.3：运行时开关收敛后仅滞销阈值/标签尺寸/完成标记类）。
 * 不做内存缓存——当前消费方（checklist）低频读取，缓存+SSE 失效广播是 YAGNI；
 * 出现高频读者（如打印页每次渲染读标签尺寸）再加。
 */
@Service
public class SettingService {

    private final SysSettingMapper mapper;

    public SettingService(SysSettingMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<String> findValue(String key) {
        return Optional.ofNullable(mapper.selectById(key)).map(SysSettingEntity::getValue);
    }

    /** 幂等 upsert：值未变不写（保 updated_at 语义——「最后一次真正变更」。 */
    public void putValue(String key, String value, Long updatedBy) {
        SysSettingEntity existing = mapper.selectById(key);
        if (existing == null) {
            SysSettingEntity entity = new SysSettingEntity();
            entity.setKey(key);
            entity.setValue(value);
            entity.setUpdatedBy(updatedBy);
            mapper.insert(entity);
        } else if (!Objects.equals(existing.getValue(), value)) {
            existing.setValue(value);
            existing.setUpdatedBy(updatedBy);
            mapper.updateById(existing);
        }
    }
}
