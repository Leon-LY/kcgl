package com.kcgl.module.dict;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PriceBandMapper extends BaseMapper<PriceBandEntity> {

    /**
     * 全表行锁（写路径互斥）：档位唯一 ≤26 行，锁全表代价可忽略——
     * 并发建/改档位时区间重叠校验与写入串行化，防两笔并发各插入一个互叠区间。
     */
    @Select("SELECT * FROM price_band ORDER BY lower_bound, id FOR UPDATE")
    List<PriceBandEntity> lockAll();
}
