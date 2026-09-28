package com.kcgl.module.yahoo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 出品记录 Mapper（uk_auction 幂等定位）。 */
@Mapper
public interface YahooListingMapper extends BaseMapper<YahooListingEntity> {
}
