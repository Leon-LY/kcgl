package com.kcgl.module.dict.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 会场创建：code 1-8 位字母数字（进管理号前段，大小写不敏感、服务层转大写）；名称自由文本。 */
public record VenueCreateRequest(
        @NotBlank @Pattern(regexp = "(?i)^[A-Z0-9]{1,8}$", message = "会場コードは半角英数字1〜8桁で入力してください") String code,
        @NotBlank @Size(max = 64) String name) {
}
