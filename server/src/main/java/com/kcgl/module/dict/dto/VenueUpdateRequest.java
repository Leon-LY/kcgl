package com.kcgl.module.dict.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 会场编辑（D-166）：name 必填；code 可空=不改码。
 * 改码只影响今后生成的管理号——已录入商品的 item_code/venue_code 是录入时快照，恒不变。
 * 输入大小写不敏感，服务层统一转大写存储（保持管理号全大写）。
 */
public record VenueUpdateRequest(
        @NotBlank @Size(max = 64) String name,
        @Pattern(regexp = "(?i)^[A-Z0-9]{1,8}$", message = "会場コードは半角英数字1〜8桁で入力してください")
        String code) {
}
