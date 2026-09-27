package com.kcgl.module.inventory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface StockLedgerMapper extends BaseMapper<StockLedgerEntity> {

    /**
     * 对账下钻（M3-②，docs/01 5.3 唯一定义）：一件一仓的流水净头寸——
     * 每行 wh_to 记 +1 / wh_from 记 −1（NULL 不入账），同件同仓求和。
     * 聚合下推 DB（GROUP BY）单遍扫，仅返回非零头寸行：内存上限≈在库件数×2，
     * 300 万流水零整表传输（六轮 DB M6：不加索引）。
     */
    @Select("SELECT item_id, wh AS warehouse, SUM(sign) AS net FROM ("
            + " SELECT item_id, wh_to AS wh, 1 AS sign FROM stock_ledger WHERE wh_to IS NOT NULL"
            + " UNION ALL"
            + " SELECT item_id, wh_from AS wh, -1 AS sign FROM stock_ledger WHERE wh_from IS NOT NULL"
            + ") t GROUP BY item_id, wh HAVING SUM(sign) <> 0")
    List<LedgerItemPosition> itemNetPositions();
}
