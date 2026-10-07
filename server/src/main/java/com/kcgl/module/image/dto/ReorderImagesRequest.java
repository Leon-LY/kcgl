package com.kcgl.module.image.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 图片重排请求（D5）：按最终顺序给出该商品**全部**图片 id。
 * 集合必须与现有一一对应（无缺失、无多余、无重复），否则 400——静默应用半序会
 * 留下「库里顺序与所见不一致」的脏序，比报错更危险（与到仓批量幂等键同纪律）。
 */
public record ReorderImagesRequest(@NotEmpty List<Long> ids) {
}
