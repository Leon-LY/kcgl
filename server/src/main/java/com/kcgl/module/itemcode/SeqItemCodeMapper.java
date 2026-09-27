package com.kcgl.module.itemcode;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SeqItemCodeMapper extends BaseMapper<SeqItemCodeEntity> {

    /** 桶行锁（7.1 核心）：同桶并发在此串行化，锁持至提交；锁内纯写毫秒级。 */
    @Select("SELECT * FROM seq_item_code WHERE venue_id = #{venueId} AND `year` = #{year} "
            + "AND month = #{month} FOR UPDATE")
    SeqItemCodeEntity lockBucket(@Param("venueId") long venueId, @Param("year") int year,
            @Param("month") int month);

    /** 建桶（并发竞争用 IGNORE 吞掉，随后重锁读取胜者）。 */
    @Insert("INSERT IGNORE INTO seq_item_code(venue_id, `year`, month, cur_prefix, cur_seq) "
            + "VALUES(#{venueId}, #{year}, #{month}, 'A', 0)")
    int insertIgnoreBucket(@Param("venueId") long venueId, @Param("year") int year,
            @Param("month") int month);
}
