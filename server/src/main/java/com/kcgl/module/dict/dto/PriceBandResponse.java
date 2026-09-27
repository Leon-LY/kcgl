package com.kcgl.module.dict.dto;

import com.kcgl.module.dict.PriceBandEntity;

public record PriceBandResponse(Long id, String code, Long lowerBound, Long upperBound, boolean enabled) {

    public static PriceBandResponse from(PriceBandEntity entity) {
        return new PriceBandResponse(entity.getId(), entity.getCode(), entity.getLowerBound(),
                entity.getUpperBound(), entity.getEnabled() != null && entity.getEnabled() == 1);
    }
}
