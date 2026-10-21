-- ---------------------------------------------------------------------
-- 会场代码放开：半角英字2桁 → 数字+英文自由组合（1-8位，D-165）
-- 管理号内嵌会场代码，故格式随之改为「月补零2位」：
--   {会场}{MM}-{前缀}{流水}{档位}  例：HT09-A1X（原 HT9-A1X）
-- 月恒2位后，变长会场段与月段的切分才无歧义（见 ItemCodeFormatter）。
-- 旧测试数据由甲方上传真实数据前清空，本迁移不对旧号做兼容改写。
-- ---------------------------------------------------------------------

ALTER TABLE auction_venue DROP CHECK chk_venue_code;

ALTER TABLE auction_venue
  MODIFY COLUMN code VARCHAR(8) NOT NULL COMMENT '会场代码,字母数字组合,如 HT / TK01 / NAGOYA2';

ALTER TABLE auction_venue
  ADD CONSTRAINT chk_venue_code CHECK (code REGEXP '^[A-Z0-9]{1,8}$');

-- 管理号快照/流水/盘点列容量：会场最长8位时管理号最长17字符，放宽到32。
ALTER TABLE item
  MODIFY COLUMN venue_code VARCHAR(8) NOT NULL COMMENT '会场码快照(生成时)';
ALTER TABLE item
  MODIFY COLUMN item_code VARCHAR(32) NOT NULL COMMENT '管理号 如HT09-A1X';
ALTER TABLE stock_ledger
  MODIFY COLUMN item_code VARCHAR(32) NOT NULL COMMENT '管理号快照';
ALTER TABLE stocktake_scan
  MODIFY COLUMN item_code VARCHAR(32) NOT NULL;
ALTER TABLE stocktake_diff
  MODIFY COLUMN item_code VARCHAR(32) NOT NULL;
