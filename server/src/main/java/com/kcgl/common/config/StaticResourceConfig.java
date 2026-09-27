package com.kcgl.common.config;

import com.kcgl.module.image.ImageProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * 图片静态直出（dev/测试环境）：/img/orig/**、/img/thumb/** 映射到落盘目录。
 * 生产 nginx 挂同一 URL 与卷直接直出（expires 30d immutable + noindex，docs/01 7.5），
 * 本映射不参与但保留——镜像在无 nginx 拓扑（本地 compose 纯 app）下同样可用。
 * URL 内嵌 128-bit UUID 不可枚举，匿名可达（A17 已决策）。
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    private final ImageProperties imageProperties;

    public StaticResourceConfig(ImageProperties imageProperties) {
        this.imageProperties = imageProperties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        CacheControl cache = CacheControl.maxAge(Duration.ofDays(30)).cachePublic();
        registry.addResourceHandler("/img/orig/**")
                .addResourceLocations(imageProperties.origRoot().toUri().toString())
                .setCacheControl(cache);
        registry.addResourceHandler("/img/thumb/**")
                .addResourceLocations(imageProperties.thumbRoot().toUri().toString())
                .setCacheControl(cache);
    }
}
