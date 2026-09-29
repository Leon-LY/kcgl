package com.kcgl.module.item;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

@Mapper
public interface ItemMapper extends BaseMapper<ItemEntity> {

    /**
     * 两仓/大盘聚合（M5-③ stats，docs/01 六节口径唯一定义）：
     * 条件聚合（MySQL 布尔→0/1）单遍扫返回一行，warehouse 传 null=全仓、1/2=单仓。
     * <ul>
     * <li>在库/在途/已出库：stock_status 0/1/2（排除作废+软删——死件不入运营口径）；
     * <li>本月入库：warehouse_in_date 业务日期落本月（D-045：顾客退回保留原入库日，
     *     不重置——「本月入库」是到仓业务事件而非状态回转）；
     * <li>滞销黄/红：在库 AND sale_status≠成交 AND 入库日非空（D-065，与
     *     ItemSearchService.applyWarnLevel 同谓词）；黄=warn≤日差＜alarm、红=日差≥alarm；
     * <li>在库货值：∑total_cost（STORED 生成列=purchase+三费用 IFNULL）；
     * <li>件均库龄：AVG(DATEDIFF(今天, 入库日))——仅统计有入库日的在库件
     *     （盘点盘盈件无入库日，天然排除），空集为 NULL。
     * </ul>
     */
    @Select("""
            <script>
            SELECT
              COUNT(*) AS totalItems,
              COALESCE(SUM(stock_status = 0), 0) AS inTransit,
              COALESCE(SUM(stock_status = 1), 0) AS inStock,
              COALESCE(SUM(stock_status = 2), 0) AS shipped,
              COALESCE(SUM(warehouse_in_date &gt;= #{monthStart} AND warehouse_in_date &lt;= #{monthEnd}), 0) AS monthInbound,
              COALESCE(SUM(stock_status = 1 AND sale_status != 2 AND warehouse_in_date IS NOT NULL
                  AND warehouse_in_date &lt;= #{warnBefore} AND warehouse_in_date &gt; #{alarmBefore}), 0) AS slowWarn,
              COALESCE(SUM(stock_status = 1 AND sale_status != 2 AND warehouse_in_date IS NOT NULL
                  AND warehouse_in_date &lt;= #{alarmBefore}), 0) AS slowRed,
              COALESCE(SUM(CASE WHEN stock_status = 1 THEN total_cost END), 0) AS stockValue,
              AVG(CASE WHEN stock_status = 1 AND warehouse_in_date IS NOT NULL
                  THEN DATEDIFF(#{today}, warehouse_in_date) END) AS avgStockAgeDays
            FROM item
            WHERE voided = 0 AND deleted = 0
            <if test="warehouse != null">AND warehouse = #{warehouse}</if>
            </script>
            """)
    ItemStatsAggregate aggregateStats(@Param("today") LocalDate today,
            @Param("warnBefore") LocalDate warnBefore,
            @Param("alarmBefore") LocalDate alarmBefore,
            @Param("monthStart") LocalDate monthStart,
            @Param("monthEnd") LocalDate monthEnd,
            @Param("warehouse") Integer warehouse);
}
