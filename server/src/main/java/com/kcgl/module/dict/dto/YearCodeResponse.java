package com.kcgl.module.dict.dto;

import com.kcgl.module.dict.YearCodeEntity;

public record YearCodeResponse(Long id, int year, String code) {

    public static YearCodeResponse from(YearCodeEntity entity) {
        return new YearCodeResponse(entity.getId(), entity.getYear(), entity.getCode());
    }
}
