package com.kcgl.module.inventory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
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

    /**
     * 本月出库数（M5-③ stats，与 item 侧「本月入库」对称的运营口径）：
     * SELL(3)/SCRAP(4)/退回拍卖场（RETURN 6 且 wh_from 非空——顾客退回仅置 wh_to、
     * 不占出库；在途退回拍卖场双侧皆空，同样不计）。created_at 为 JST 墙钟
     * （服务写入走 JST Clock），窗口 [from, to) 左闭右开；warehouse 传 null=全仓。
     * VOID/回收站等治理动作不入运营出库口径。
     */
    @Select("""
            <script>
            SELECT COALESCE(SUM(txn_type IN (3, 4, 6) AND wh_from IS NOT NULL
                AND created_at &gt;= #{from} AND created_at &lt; #{to}
                <if test="warehouse != null">AND wh_from = #{warehouse}</if>
                ), 0)
            FROM stock_ledger
            </script>
            """)
    long monthOutbound(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("warehouse") Integer warehouse);
}
