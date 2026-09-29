-- ---------------------------------------------------------------------
-- V4｜D-069：雅虎导入目标格式从「出品状态 CSV」重校准为「受注（订单）xlsx」
-- ---------------------------------------------------------------------

-- 1. yahoo_listing 行级幂等键放宽：UNIQUE(yahoo_auction_id) → UNIQUE(item_id, yahoo_auction_id)。
--    まとめ売り=1 拍卖 N 件，同 auction_id 须落 N 行（库存语义=每件都已卖出）。
--    item_id=NULL（未匹配占位行）不受唯一索引保护（MySQL NULL 不参与唯一判定），
--    由导入单线程管线的代码级 UPSERT 保证幂等（docs/01 7.4）。
-- 2. order_id 留痕：受注表 A 列（雅虎受注 ID），出荷待ち/照合/单件历史展示。
-- 3. 语义重校：受注表=已成交事实集——status 恒 2、listed_at 恒 NULL、
--    closed_at=OrderTime（成交时刻）、sold_price=UnitPrice（まとめ売り=NULL 手填后补）。
ALTER TABLE yahoo_listing
  ADD COLUMN order_id VARCHAR(32) NULL COMMENT '雅虎受注ID(留痕展示)' AFTER yahoo_auction_id,
  DROP KEY uk_auction,
  ADD UNIQUE KEY uk_item_auction (item_id, yahoo_auction_id),
  COMMENT='雅虎受注记录(一订单商品一行,(商品,拍卖)对幂等)';

-- 4. encoding_detected 退役：xlsx=zip+UTF-8 XML 自描述，编码检测链整体退场（D-069 1）。
-- 5. note 補足列：まとめ売り「複数商品のため単価未分割」等批次级提示
--    （对齐 V2 excel_import_batch.note 同口径）。
ALTER TABLE yahoo_import_batch
  DROP COLUMN encoding_detected,
  ADD COLUMN note VARCHAR(500) NULL COMMENT 'まとめ売り等批次级補足(500字截断)' AFTER error_rows;
