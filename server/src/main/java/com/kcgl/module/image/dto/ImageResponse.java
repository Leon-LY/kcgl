package com.kcgl.module.image.dto;

import com.kcgl.module.image.ImageEntity;

/**
 * 图片响应。url/thumbUrl 为同源相对路径：dev 由 Spring 资源映射直出
 * （StaticResourceConfig），生产由 nginx 挂同一 URL 直出（docs/01 7.5）。
 */
public record ImageResponse(
        Long id,
        String clientUuid,
        Long itemId,
        String url,
        String thumbUrl,
        Integer imageType,
        Integer sortOrder) {

    public static ImageResponse from(ImageEntity entity) {
        return new ImageResponse(
                entity.getId(),
                entity.getClientUuid(),
                entity.getItemId(),
                "/img/orig/" + entity.getStoredPath(),
                "/img/thumb/" + entity.getThumbPath(),
                entity.getImageType(),
                entity.getSortOrder());
    }
}
