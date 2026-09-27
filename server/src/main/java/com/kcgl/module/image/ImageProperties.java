package com.kcgl.module.image;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 图片管线配置（docs/01 7.5）。dir 为落盘根目录，其下 orig/（重编码后原图）与
 * thumb/（256px 缩略图）两子目录；生产由部署卷挂载注入（KCGL_IMAGE_DIR）。
 */
@ConfigurationProperties(prefix = "kcgl.image")
public record ImageProperties(String dir, Long maxUploadBytes, Integer maxPixels) {

    public ImageProperties {
        if (dir == null || dir.isBlank()) {
            dir = "./data/images";
        }
        if (maxUploadBytes == null) {
            maxUploadBytes = 5_242_880L; // 5MB
        }
        if (maxPixels == null) {
            maxPixels = 8000;
        }
    }

    public Path origRoot() {
        return Path.of(dir, "orig");
    }

    public Path thumbRoot() {
        return Path.of(dir, "thumb");
    }
}
