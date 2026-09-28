package com.kcgl.module.stocktake;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface StocktakeMapper extends BaseMapper<StocktakeEntity> {

    /**
     * 行锁读取单条盘点单：状态迁移操作（scan/close/cancel/resolve）统一先锁本行——
     * 同单操作串行化后，close 的差异计算与 resolve 的「全处理完转已确认」计数
     * 都建立在锁内一致快照上（最后两个并发 CONFIRM 的转态竞态由此消除）。
     */
    @Select("SELECT * FROM stocktake WHERE id = #{id} FOR UPDATE")
    StocktakeEntity lockById(long id);

    /**
     * 同仓进行中单探测（FOR UPDATE）：无索引小表全扫锁全部行+间隙——发起时序上
     * 串行化「查无→插入」，两并发发起同仓必有一方看到对方的行（409009）；
     * 当日序号（PD+日期+序号）计数同受此锁保护，uk_no 仅作兜底。
     */
    @Select("SELECT id FROM stocktake WHERE warehouse = #{warehouse} AND status = 0 FOR UPDATE")
    List<Long> lockActiveIds(int warehouse);
}
