package com.kcgl.module.dict.dto;

import com.kcgl.module.dict.VenueEntity;

public record VenueResponse(Long id, String code, String name, boolean enabled) {

    public static VenueResponse from(VenueEntity entity) {
        return new VenueResponse(entity.getId(), entity.getCode(), entity.getName(),
                entity.getEnabled() != null && entity.getEnabled() == 1);
    }
}
