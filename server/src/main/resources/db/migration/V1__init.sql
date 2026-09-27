-- =====================================================================
-- V1__init.sql 全量建表（17 表）+ 种子数据
-- 约定：金额 INT UNSIGNED（日元）；时间 DATETIME(3) 全库 JST（应用层 Asia/Tokyo）；
--      禁 TIMESTAMP 列（实体一律 LocalDateTime）；物理外键不用（逻辑外键+索引）；
--      排序规则全库统一 utf8mb4_0900_ai_ci；流水/日志表只增不改不删。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. sys_user 用户
-- ---------------------------------------------------------------------
CREATE TABLE sys_user (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(32) NOT NULL,
  password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt(12)',
  display_name VARCHAR(64) NOT NULL,
  role TINYINT UNSIGNED NOT NULL COMMENT '1管理员 2可编辑 3仅查看',
  locale VARCHAR(8) NOT NULL DEFAULT 'ja-JP',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '停用=0,用户只停用不物理删',
  must_change_pwd TINYINT NOT NULL DEFAULT 0,
  failed_attempts INT UNSIGNED NOT NULL DEFAULT 0,
  locked_until DATETIME(3) NULL,
  last_login_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_username (username),
  CONSTRAINT chk_user_role CHECK (role IN (1,2,3)),
  CONSTRAINT chk_user_locale CHECK (locale IN ('ja-JP','zh-CN','en-US'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户';

-- ---------------------------------------------------------------------
-- 2. auction_venue 拍卖场（会场）
-- ---------------------------------------------------------------------
CREATE TABLE auction_venue (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  code CHAR(2) NOT NULL COMMENT '会场代码,如 HT',
  name VARCHAR(64) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '有商品引用只停用不物理删',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_code (code),
  CONSTRAINT chk_venue_code CHECK (code REGEXP '^[A-Z]{2}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='拍卖场';

-- ---------------------------------------------------------------------
-- 3. year_code 年份代号对照（2016=A 起不跳 I/O）
-- ---------------------------------------------------------------------
CREATE TABLE year_code (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  `year` SMALLINT UNSIGNED NOT NULL,
  code CHAR(1) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_year (`year`),
  UNIQUE KEY uk_code (code),
  CONSTRAINT chk_yearcode CHECK (code REGEXP '^[A-Z]$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='年份代号';

-- 种子：2016=A … 2041=Z（不跳 I/O；2026=K、2027=L 与需求示例吻合）
INSERT INTO year_code (`year`, code) VALUES
 (2016,'A'),(2017,'B'),(2018,'C'),(2019,'D'),(2020,'E'),(2021,'F'),(2022,'G'),(2023,'H'),(2024,'I'),(2025,'J'),
 (2026,'K'),(2027,'L'),(2028,'M'),(2029,'N'),(2030,'O'),(2031,'P'),(2032,'Q'),(2033,'R'),(2034,'S'),(2035,'T'),
 (2036,'U'),(2037,'V'),(2038,'W'),(2039,'X'),(2040,'Y'),(2041,'Z');

-- ---------------------------------------------------------------------
-- 4. price_band 价格档位（左闭右开；NULL=开区间端；只停用不删、不回溯历史）
-- ---------------------------------------------------------------------
CREATE TABLE price_band (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  code CHAR(1) NOT NULL,
  lower_bound INT UNSIGNED NULL COMMENT '含；NULL=首档无下限',
  upper_bound INT UNSIGNED NULL COMMENT '不含；NULL=末档无上限',
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_code (code),
  CONSTRAINT chk_band_code CHECK (code REGEXP '^[A-Z]$'),
  CONSTRAINT chk_band_range CHECK (lower_bound IS NULL OR upper_bound IS NULL OR lower_bound < upper_bound)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='价格档位';

-- ---------------------------------------------------------------------
-- 5. item 商品主表（核心）
-- ---------------------------------------------------------------------
CREATE TABLE item (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  item_code      VARCHAR(16)  NOT NULL COMMENT '管理号 如HTK9-A1X',
  venue_id       BIGINT UNSIGNED NOT NULL,
  venue_code     CHAR(2) NOT NULL COMMENT '会场码快照(生成时)',
  `year` SMALLINT UNSIGNED NOT NULL,
  year_code      CHAR(1) NOT NULL COMMENT '年代号快照',
  buy_month      TINYINT UNSIGNED NOT NULL COMMENT '1-12 不补零',
  seq_prefix     VARCHAR(3) NOT NULL DEFAULT 'A',
  seq_no         SMALLINT UNSIGNED NOT NULL,
  buy_date       DATE NOT NULL,
  photo_date     DATE NULL COMMENT 'NULL=未拍摄',
  purchase_price INT UNSIGNED NOT NULL COMMENT '进货单价(档位依据,应用层上限99999999)',
  fee            INT UNSIGNED NULL,
  shipping_fee   INT UNSIGNED NULL,
  tax            INT UNSIGNED NULL,
  sold_price     INT UNSIGNED NULL COMMENT '成交价(CSV>手填);置于生成列之前防脆弱列序依赖',
  total_cost     INT UNSIGNED AS (purchase_price + IFNULL(fee,0) + IFNULL(shipping_fee,0) + IFNULL(tax,0)) STORED,
  profit         BIGINT AS (CAST(sold_price AS SIGNED) - CAST(purchase_price AS SIGNED) - CAST(IFNULL(fee,0) AS SIGNED) - CAST(IFNULL(shipping_fee,0) AS SIGNED) - CAST(IFNULL(tax,0) AS SIGNED)) STORED COMMENT '未售=NULL自然传播',
  price_band_code CHAR(1) NOT NULL COMMENT '录入时档位快照,不随档位表回溯',
  warehouse      TINYINT UNSIGNED NOT NULL COMMENT '1名古屋 2福岡',
  shelf_no       VARCHAR(32) NULL,
  warehouse_in_date DATE NULL COMMENT '入库日期,滞销起算',
  group_no       VARCHAR(32) NULL COMMENT '同款组号',
  remark         VARCHAR(500) NULL,
  -- 扩展属性（甲方确认将来启用,全部可空不阻塞录入;语义细化后仅做加法迁移）
  item_name      VARCHAR(200) NULL COMMENT '商品名称',
  category       VARCHAR(64)  NULL COMMENT '分类',
  author_kiln    VARCHAR(128) NULL COMMENT '作者/窑口',
  size_text      VARCHAR(64)  NULL COMMENT '尺寸描述(自由文本,如 高さ12cm 幅8cm)',
  weight_g       INT UNSIGNED NULL COMMENT '重量(克)',
  sales_channel  VARCHAR(32)  NULL COMMENT '销售渠道',
  re_entry_of    BIGINT UNSIGNED NULL COMMENT '作废重录前后件互链',
  ext_json       JSON NULL COMMENT '通用扩展位(承接独立列之外的额外属性)',
  stock_status   TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0在途 1在库 2已出库',
  sale_status    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0未上架 1在售 2成交 3取消',
  yahoo_item_id  VARCHAR(32) NULL,
  list_price     INT UNSIGNED NULL COMMENT '雅虎挂牌价快照',
  yahoo_listing_count INT UNSIGNED NOT NULL DEFAULT 0,
  yahoo_cancel_count  INT UNSIGNED NOT NULL DEFAULT 0,
  yahoo_last_synced_at DATETIME(3) NULL,
  voided         TINYINT NOT NULL DEFAULT 0 COMMENT '作废(冻结,禁一切变动)',
  void_reason    VARCHAR(255) NULL,
  void_re_entry  BIGINT UNSIGNED NULL COMMENT '作废后重录的新件id(冗余反链,主链为re_entry_of)',
  deleted        TINYINT NOT NULL DEFAULT 0 COMMENT '回收站软删',
  deleted_at     DATETIME(3) NULL,
  deleted_by     BIGINT UNSIGNED NULL,
  created_by     BIGINT UNSIGNED NOT NULL,
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by     BIGINT UNSIGNED NULL,
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version        INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁',
  UNIQUE KEY uk_item_code (item_code),
  KEY idx_wh_stock (warehouse, stock_status, deleted),
  KEY idx_sale (sale_status, deleted),
  KEY idx_buy_date (buy_date),
  KEY idx_in_date (warehouse_in_date),
  KEY idx_group (group_no),
  KEY idx_yahoo_id (yahoo_item_id),
  KEY idx_creator (created_by, created_at),
  CONSTRAINT chk_item_month CHECK (buy_month BETWEEN 1 AND 12),
  CONSTRAINT chk_item_seq CHECK (seq_no BETWEEN 1 AND 99),
  CONSTRAINT chk_item_wh CHECK (warehouse IN (1,2)),
  CONSTRAINT chk_item_stock CHECK (stock_status IN (0,1,2)),
  CONSTRAINT chk_item_sale CHECK (sale_status IN (0,1,2,3)),
  CONSTRAINT chk_item_void_del CHECK (voided IN (0,1) AND deleted IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品主表';

-- ---------------------------------------------------------------------
-- 6. seq_item_code 管理号计数器（一桶一行,FOR UPDATE 行锁）
-- ---------------------------------------------------------------------
CREATE TABLE seq_item_code (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  venue_id BIGINT UNSIGNED NOT NULL,
  `year` SMALLINT UNSIGNED NOT NULL,
  month TINYINT UNSIGNED NOT NULL,
  cur_prefix VARCHAR(3) NOT NULL DEFAULT 'A',
  cur_seq SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已用最大流水',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_bucket (venue_id, `year`, month),
  CONSTRAINT chk_seq_month CHECK (month BETWEEN 1 AND 12),
  CONSTRAINT chk_seq_cur CHECK (cur_seq BETWEEN 0 AND 99)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理号计数器';

-- ---------------------------------------------------------------------
-- 7. item_image 商品图片
-- ---------------------------------------------------------------------
CREATE TABLE item_image (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  item_id BIGINT UNSIGNED NOT NULL,
  client_uuid CHAR(36) NOT NULL COMMENT '上传幂等键',
  stored_path VARCHAR(255) NOT NULL COMMENT '{yyyy}/{MM}/{uuid}.jpg',
  thumb_path VARCHAR(255) NOT NULL,
  image_type TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1品照片 2底款照片(预留)',
  sort_order INT UNSIGNED NOT NULL DEFAULT 0,
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_client_uuid (client_uuid),
  KEY idx_item (item_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品图片';

-- ---------------------------------------------------------------------
-- 8. stock_ledger 库存流水（不可变；对账规则：每行 wh_from 记 -1、wh_to 记 +1，NULL 不入账）
-- ---------------------------------------------------------------------
CREATE TABLE stock_ledger (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  client_req_id CHAR(36) NULL COMMENT '写操作幂等键,命中先读回原结果(唯一定义见技术方案7.0)',
  txn_type TINYINT UNSIGNED NOT NULL COMMENT '1录入2到仓3卖出4报废5调拨6退货7盘点调整8作废9上架标记10成交标记11取消标记12手工修正13回收站删除14回收站恢复',
  item_id BIGINT UNSIGNED NOT NULL,
  item_code VARCHAR(16) NOT NULL COMMENT '管理号快照',
  stock_from TINYINT UNSIGNED NULL,
  stock_to   TINYINT UNSIGNED NULL,
  sale_from  TINYINT UNSIGNED NULL,
  sale_to    TINYINT UNSIGNED NULL,
  wh_from    TINYINT UNSIGNED NULL COMMENT '出账仓(在途=NULL)',
  wh_to      TINYINT UNSIGNED NULL COMMENT '入账仓',
  qty_change TINYINT NOT NULL DEFAULT 0 COMMENT '件数净变化(信息列;对账恒用 wh_from/wh_to 双向入账)',
  reason     VARCHAR(255) NULL COMMENT '报废原因/退货说明/修正原因',
  return_direction TINYINT UNSIGNED NULL COMMENT '1顾客退回 2退回拍卖场',
  ref_type   VARCHAR(24) NULL COMMENT 'YAHOO_LISTING / STOCKTAKE / ITEM 等',
  ref_id     BIGINT UNSIGNED NULL,
  operator_id BIGINT UNSIGNED NOT NULL,
  operator_name VARCHAR(64) NOT NULL COMMENT '操作人快照(异步线程显式传参)',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_client_req (client_req_id),
  KEY idx_item (item_id),
  KEY idx_created (created_at),
  CONSTRAINT chk_ledger_txn CHECK (txn_type BETWEEN 1 AND 14),
  CONSTRAINT chk_ledger_qty CHECK (qty_change BETWEEN -1 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存流水(只增不改不删)';

-- ---------------------------------------------------------------------
-- 9. yahoo_listing 雅虎出品记录（一次出品一行）
-- ---------------------------------------------------------------------
CREATE TABLE yahoo_listing (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  yahoo_auction_id VARCHAR(32) NOT NULL COMMENT '雅虎商品ID,行级幂等键',
  item_id BIGINT UNSIGNED NULL COMMENT '匹配到的商品;未匹配=NULL',
  raw_item_code VARCHAR(32) NULL COMMENT 'CSV 原文管理号(未匹配行展示原文)',
  item_code VARCHAR(32) NULL COMMENT '归一化后管理号',
  list_price INT UNSIGNED NULL,
  sold_price INT UNSIGNED NULL,
  status TINYINT UNSIGNED NOT NULL COMMENT '1在售 2成交 3取消',
  listed_at DATETIME(3) NULL,
  closed_at DATETIME(3) NULL,
  first_seen_batch_id BIGINT UNSIGNED NULL,
  last_seen_batch_id  BIGINT UNSIGNED NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_auction (yahoo_auction_id),
  KEY idx_item (item_id),
  CONSTRAINT chk_listing_status CHECK (status IN (1,2,3))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='雅虎出品记录';

-- ---------------------------------------------------------------------
-- 10. yahoo_import_batch CSV 导入批次
-- ---------------------------------------------------------------------
CREATE TABLE yahoo_import_batch (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  file_sha256 CHAR(64) NOT NULL COMMENT '同文件重复上传 409',
  original_filename VARCHAR(255) NOT NULL,
  encoding_detected VARCHAR(16) NULL,
  status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0处理中 1完成 2失败',
  row_count INT UNSIGNED NOT NULL DEFAULT 0,
  matched_count INT UNSIGNED NOT NULL DEFAULT 0,
  unmatched_count INT UNSIGNED NOT NULL DEFAULT 0,
  updated_count INT UNSIGNED NOT NULL DEFAULT 0,
  error_rows JSON NULL COMMENT '采样:前1000条+计数',
  error_message VARCHAR(500) NULL,
  uploaded_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3) NULL,
  UNIQUE KEY uk_sha (file_sha256),
  CONSTRAINT chk_batch_status CHECK (status IN (0,1,2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='雅虎CSV导入批次';

-- ---------------------------------------------------------------------
-- 11. stocktake 盘点单
-- ---------------------------------------------------------------------
CREATE TABLE stocktake (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  stocktake_no VARCHAR(24) NOT NULL COMMENT 'PD+日期+序号',
  warehouse TINYINT UNSIGNED NOT NULL,
  status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0进行中 1待确认 2已确认 3作废',
  expected_count INT UNSIGNED NULL COMMENT 'close 时冻结的期望数',
  scanned_count INT UNSIGNED NOT NULL DEFAULT 0,
  diff_count INT UNSIGNED NULL,
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  closed_at DATETIME(3) NULL,
  closed_by BIGINT UNSIGNED NULL,
  UNIQUE KEY uk_no (stocktake_no),
  CONSTRAINT chk_st_wh CHECK (warehouse IN (1,2)),
  CONSTRAINT chk_st_status CHECK (status IN (0,1,2,3))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='盘点单';

-- ---------------------------------------------------------------------
-- 12. stocktake_scan 盘点扫码
-- ---------------------------------------------------------------------
CREATE TABLE stocktake_scan (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  stocktake_id BIGINT UNSIGNED NOT NULL,
  item_id BIGINT UNSIGNED NOT NULL,
  item_code VARCHAR(16) NOT NULL,
  scanned_by BIGINT UNSIGNED NOT NULL,
  scanned_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_st_item (stocktake_id, item_id),
  KEY idx_st (stocktake_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='盘点扫码(重复扫码返回repeated)';

-- ---------------------------------------------------------------------
-- 13. stocktake_diff 盘点差异
-- ---------------------------------------------------------------------
CREATE TABLE stocktake_diff (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  stocktake_id BIGINT UNSIGNED NOT NULL,
  item_id BIGINT UNSIGNED NOT NULL,
  item_code VARCHAR(16) NOT NULL,
  diff_type TINYINT UNSIGNED NOT NULL COMMENT '1盘亏 2盘盈 3仓库不符 4冻结品',
  expected_wh TINYINT UNSIGNED NULL,
  actual_wh TINYINT UNSIGNED NULL,
  note VARCHAR(255) NULL COMMENT '含「盘点期间发生过变动流水」标注',
  confirm_status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0待确认 1确认调整 2忽略',
  adjust_ledger_id BIGINT UNSIGNED NULL COMMENT 'CONFIRM 生成的 STOCKTAKE_ADJUST 回链',
  confirmed_by BIGINT UNSIGNED NULL,
  confirmed_at DATETIME(3) NULL,
  KEY idx_st (stocktake_id, confirm_status),
  CONSTRAINT chk_diff_type CHECK (diff_type BETWEEN 1 AND 4),
  CONSTRAINT chk_diff_confirm CHECK (confirm_status IN (0,1,2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='盘点差异(人工确认才调账)';

-- ---------------------------------------------------------------------
-- 14. operation_log 操作日志（不可变）
-- ---------------------------------------------------------------------
CREATE TABLE operation_log (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  action VARCHAR(48) NOT NULL,
  entity_type VARCHAR(32) NOT NULL,
  entity_id BIGINT UNSIGNED NULL,
  detail JSON NULL COMMENT '前后值 diff',
  operator_id BIGINT UNSIGNED NOT NULL,
  operator_name VARCHAR(64) NOT NULL,
  ip VARCHAR(45) NULL,
  ua VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_entity (entity_type, entity_id),
  KEY idx_created (created_at),
  KEY idx_operator (operator_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作日志(只增不改不删)';

-- ---------------------------------------------------------------------
-- 15. sys_setting 系统设置（收敛后仅 5 项）
-- ---------------------------------------------------------------------
CREATE TABLE sys_setting (
  `key` VARCHAR(64) NOT NULL,
  `value` VARCHAR(255) NOT NULL,
  updated_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统设置';

INSERT INTO sys_setting (`key`, `value`) VALUES
 ('slow_move.warn_days', '30'),
 ('slow_move.alarm_days', '90'),
 ('label.preset', '38x21'),
 ('label.width_mm', '38'),
 ('label.height_mm', '21');

-- ---------------------------------------------------------------------
-- 16. sys_alert 统一告警
-- ---------------------------------------------------------------------
CREATE TABLE sys_alert (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  type VARCHAR(32) NOT NULL COMMENT 'RECONCILE_MISMATCH/BACKUP_STALE/DISK_USAGE/...',
  dedup_key VARCHAR(64) NOT NULL COMMENT '同键去重只留最新一条开启态',
  level TINYINT UNSIGNED NOT NULL COMMENT '1提示 2警告 3错误',
  message VARCHAR(500) NOT NULL,
  payload JSON NULL,
  status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0开启 1已读',
  read_by BIGINT UNSIGNED NULL,
  read_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_dedup (dedup_key),
  KEY idx_created (created_at),
  CONSTRAINT chk_alert_level CHECK (level BETWEEN 1 AND 3),
  CONSTRAINT chk_alert_status CHECK (status IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一告警落库';

-- ---------------------------------------------------------------------
-- 17. client_error 前端错误上报
-- ---------------------------------------------------------------------
CREATE TABLE client_error (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  message VARCHAR(1000) NOT NULL,
  stack TEXT NULL COMMENT '截断',
  route VARCHAR(255) NULL,
  ua VARCHAR(255) NULL,
  locale VARCHAR(8) NULL,
  app_version VARCHAR(32) NULL,
  user_id BIGINT UNSIGNED NULL,
  error_id VARCHAR(16) NULL COMMENT '与后端 errorId 闭环',
  queue_pending INT UNSIGNED NULL COMMENT '上传队列积压数',
  queue_oldest_age_sec INT UNSIGNED NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='前端错误上报(30天清理)';
