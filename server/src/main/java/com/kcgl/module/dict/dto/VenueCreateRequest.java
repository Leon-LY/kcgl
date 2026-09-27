package com.kcgl.module.dict.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 会场创建/改名：code 两位大写拉丁（进管理号前两位，锁定后不可改）；名称自由文本。 */
public record VenueCreateRequest(
        @NotBlank @Pattern(regexp = "^[A-Z]{2}$", message = "会場コードは半角英字2桁で入力してください") String code,
        @NotBlank @Size(max = 64) String name) {
}
