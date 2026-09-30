-- =====================================================================
-- kcgl 压测造数自检（M7-④，D-078）——seed-bench.sql 跑完后必跑
--
-- 前三条不变量与 restore.sh run_assertions() / LedgerConsistencyService
-- 三方同口径（逐件头寸向量/计数器倒退/桶行丢失）——造数数据必须像
-- 真实数据一样通过生产级对账断言，失败即 seed-bench.sql 的 bug。
-- 后三条为造数特有（管理号格式/图片孤儿/状态-动作自洽）。
--
-- 跑法：
--   docker compose exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" kcgl \
--     < bench/bench-verify.sql
-- 判定：六项断言全部 OK + 分布报告与规格偏差 <1 个百分点。
-- =====================================================================

-- 报告里的日文标签要能正确回显（客户端字符集见 seed-bench.sql 同款说明）
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 一、不变量断言（六项，全部期望 FAIL 数 = 0）
-- ---------------------------------------------------------------------
SELECT 'A1 逐件头寸向量（LedgerConsistencyService 同口径）' AS assertion,
       CASE WHEN COUNT(*) = 0 THEN 'OK' ELSE 'FAIL' END AS verdict, COUNT(*) AS violations
FROM (
  SELECT t.item_id
  FROM (
    SELECT item_id,
           SUM(CASE WHEN wh_to = 1 THEN 1 WHEN wh_from = 1 THEN -1 ELSE 0 END) AS p1,
           SUM(CASE WHEN wh_to = 2 THEN 1 WHEN wh_from = 2 THEN -1 ELSE 0 END) AS p2
    FROM stock_ledger GROUP BY item_id
  ) t
  LEFT JOIN item i ON i.id = t.item_id
  WHERE NOT (
    (i.id IS NOT NULL AND i.stock_status = 1 AND i.deleted = 0 AND i.voided = 0
       AND ((i.warehouse = 1 AND t.p1 = 1 AND t.p2 = 0) OR (i.warehouse = 2 AND t.p1 = 0 AND t.p2 = 1)))
    OR (i.id IS NOT NULL AND (i.stock_status <> 1 OR i.deleted = 1 OR i.voided = 1) AND t.p1 = 0 AND t.p2 = 0)
  )
) drift;

SELECT 'A2 计数器倒退（cur_seq < 桶内当前前缀 MAX(seq_no)）' AS assertion,
       CASE WHEN COUNT(*) = 0 THEN 'OK' ELSE 'FAIL' END AS verdict, COUNT(*) AS violations
FROM (
  SELECT s.id
  FROM seq_item_code s JOIN item i
    ON i.venue_id = s.venue_id AND i.buy_month = s.month AND i.seq_prefix = s.cur_prefix
  GROUP BY s.id, s.cur_seq
  HAVING s.cur_seq < MAX(i.seq_no)
) backward;

SELECT 'A3 桶行丢失（item 有桶无计数器行）' AS assertion,
       CASE WHEN COUNT(*) = 0 THEN 'OK' ELSE 'FAIL' END AS verdict, COUNT(*) AS violations
FROM (
  SELECT DISTINCT i.id
  FROM item i LEFT JOIN seq_item_code s
    ON s.venue_id = i.venue_id AND s.month = i.buy_month
  WHERE s.id IS NULL
) missing_bucket;

SELECT 'A4 管理号格式（{会场2}{月}-{前缀}{流水}{价格码}）' AS assertion,
       CASE WHEN COUNT(*) = 0 THEN 'OK' ELSE 'FAIL' END AS verdict, COUNT(*) AS violations
FROM item
-- 正则一律 REGEXP_LIKE(...,'c') 强制大小写敏感：item_code 排序规则是
-- utf8mb4_0900_ai_ci（大小写不敏感），裸 REGEXP 下 [A-Z] 连小写一起放行——
-- D-080 的越界前缀（a-z 与 ASCII 91..96）正是这样从本断言眼皮下溜过，
-- 直到撞 uk_item_code 才爆出来
WHERE NOT REGEXP_LIKE(item_code,
          '^([A-Z]{2})(1[0-2]|[1-9])-([A-Z]{1,3})([1-9][0-9]?)([A-Z])?$', 'c')
   OR item_code NOT LIKE CONCAT(venue_code, buy_month, '-%')
   OR NOT REGEXP_LIKE(item_code, CONCAT('-', seq_prefix, seq_no, '([A-Z])?$'), 'c')
   OR price_band_code IS NULL
   OR NOT REGEXP_LIKE(item_code, CONCAT(price_band_code, '$'), 'c');

SELECT 'A5 图片孤儿（item_image 引用不存在的 item）' AS assertion,
       CASE WHEN COUNT(*) = 0 THEN 'OK' ELSE 'FAIL' END AS verdict, COUNT(*) AS violations
FROM item_image im LEFT JOIN item i ON i.id = im.item_id
WHERE i.id IS NULL;

SELECT 'A6 状态-动作自洽（在库必有 ARRIVE；在途不得有入账行；已出库必有终结动作）' AS assertion,
       CASE WHEN COUNT(*) = 0 THEN 'OK' ELSE 'FAIL' END AS verdict, COUNT(*) AS violations
FROM (
  -- 在库件无 ARRIVE
  SELECT i.id FROM item i
  WHERE i.stock_status = 1 AND i.deleted = 0 AND i.voided = 0
    AND NOT EXISTS (SELECT 1 FROM stock_ledger l WHERE l.item_id = i.id AND l.txn_type = 2)
  UNION ALL
  -- 在途件有仓账（wh_from/wh_to 非空）——CREATE 之外不应有任何入账
  SELECT i.id FROM item i
  WHERE i.stock_status = 0 AND i.deleted = 0 AND i.voided = 0
    AND EXISTS (SELECT 1 FROM stock_ledger l
                WHERE l.item_id = i.id AND (l.wh_from IS NOT NULL OR l.wh_to IS NOT NULL)
                  AND l.txn_type <> 1)
  UNION ALL
  -- 已出库件无终结动作（SELL/SCRAP/RETURN_VENUE 任一）
  SELECT i.id FROM item i
  WHERE i.stock_status = 2 AND i.deleted = 0 AND i.voided = 0
    AND NOT EXISTS (SELECT 1 FROM stock_ledger l WHERE l.item_id = i.id AND l.txn_type IN (3, 4))
    AND NOT EXISTS (SELECT 1 FROM stock_ledger l
                    WHERE l.item_id = i.id AND l.txn_type = 6 AND l.return_direction = 2)
) bad_state;

-- ---------------------------------------------------------------------
-- 二、分布报告（对照规格：会场 60/25/15、状态 10/55/35、图片均值 2.5、
--     备注 60%、仓库 70/30；期望偏差 <1 个百分点——CRC32 派生的均匀性
--     在 5 万样本下的正常波动范围）
-- ---------------------------------------------------------------------
SELECT '规模' AS metric,
       (SELECT COUNT(*) FROM item) AS v1,
       (SELECT COUNT(*) FROM item_image) AS v2,
       (SELECT COUNT(*) FROM stock_ledger) AS v3;

SELECT '会场分布（规格 HT60/MK25/EX15）' AS metric, venue_code,
       COUNT(*) AS cnt, ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM item), 1) AS pct
FROM item GROUP BY venue_code;

SELECT '状态分布（规格 在途10/在库55/已出库35）' AS metric,
       CASE stock_status WHEN 0 THEN '在途' WHEN 1 THEN '在库' ELSE '已出库' END AS st,
       COUNT(*) AS cnt, ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM item), 1) AS pct
FROM item GROUP BY stock_status;

SELECT '仓库分布（规格 名古屋70/福岡30）' AS metric, warehouse,
       COUNT(*) AS cnt, ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM item), 1) AS pct
FROM item GROUP BY warehouse;

SELECT '图片均值（规格 2.5）' AS metric,
       ROUND((SELECT COUNT(*) FROM item_image) / (SELECT COUNT(*) FROM item), 3) AS avg_per_item,
       (SELECT COUNT(*) FROM item WHERE photo_date IS NOT NULL) AS photo_dated_items;

SELECT '备注率（规格 60%）/均长（规格 ~120）' AS metric,
       ROUND(SUM(remark IS NOT NULL) * 100.0 / COUNT(*), 1) AS fill_pct,
       ROUND(AVG(CHAR_LENGTH(remark)), 1) AS avg_len
FROM item;

SELECT '日期跨度（规格 18 个月）' AS metric,
       MIN(buy_date) AS first_day, MAX(buy_date) AS last_day,
       TIMESTAMPDIFF(MONTH, MIN(buy_date), MAX(buy_date)) + 1 AS month_span
FROM item;

SELECT 'ledger 动作构成' AS metric,
       CASE txn_type WHEN 1 THEN '录入' WHEN 2 THEN '到仓' WHEN 3 THEN '卖出' WHEN 4 THEN '报废'
                     WHEN 5 THEN '调拨' WHEN 6 THEN '退货' ELSE '上架' END AS act,
       COUNT(*) AS cnt
FROM stock_ledger GROUP BY txn_type;
