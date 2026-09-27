package com.kcgl.module.dict.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 会场改名（code 不可改：是管理号快照来源）。 */
public record VenueRenameRequest(
        @NotBlank @Size(max = 64) String name) {
}
