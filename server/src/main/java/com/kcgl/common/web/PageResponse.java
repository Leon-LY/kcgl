package com.kcgl.common.web;

import java.util.List;

/**
 * 分页信封（docs/01 六节统一响应契约）：所有列表端点共用。
 */
public record PageResponse<T>(List<T> list, long total, int page, int size) {

    public static <T> PageResponse<T> of(List<T> list, long total, int page, int size) {
        return new PageResponse<>(list, total, page, size);
    }
}
