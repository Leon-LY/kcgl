package com.kcgl.module.image;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 每件首图（sort_order 最小）缩略图 URL 读取：打印列表/到货核对/本日会话共用的列表展示关注点。
 * 返回 URL 前缀 /img/thumb/{thumbPath}（nginx/Vite 代理直出，docs/01 7.5）。
 */
@Component
public class FirstThumbReader {

    private final ImageMapper imageMapper;

    public FirstThumbReader(ImageMapper imageMapper) {
        this.imageMapper = imageMapper;
    }

    /** 空 id 列表短路避免 IN ()；同件多图取 sort_order 最小者。 */
    public Map<Long, String> byItemIds(List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        return imageMapper.selectList(new LambdaQueryWrapper<ImageEntity>()
                        .in(ImageEntity::getItemId, itemIds)
                        .orderByAsc(ImageEntity::getItemId)
                        .orderByAsc(ImageEntity::getSortOrder))
                .stream()
                .collect(Collectors.toMap(
                        ImageEntity::getItemId,
                        image -> "/img/thumb/" + image.getThumbPath(),
                        (first, later) -> first));
    }
}
