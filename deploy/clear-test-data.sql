-- =====================================================================
-- 清空测试数据（D-169）——甲方上传真实数据前执行一次
--
-- 用法（在 /opt/kcgl 下、以 docker compose 根口令执行；不可逆，先跑 backup.sh）：
--   source db-cli.sh
--   kcgl_mysql_import "$MYSQL_ROOT_PASSWORD" kcgl < clear-test-data.sql
--
-- 保留：
--   sys_user      —— 全部保留（管理员/演示账号可继续登录）；只删压测用 bench-op*
--   sys_setting   —— 运行配置不动
--   schema        —— 结构与 Flyway 版本表不动
-- 清空：
--   全部商品与流水、导入批次、棚卸、操作日志、告警等业务表
--   auction_venue / price_band —— 甲方在「会場管理」「価格帯管理」自行登记
--
-- 说明：
--   * 商品图片行随 item_image 清空；磁盘上的历史图片文件不自动删（无引用、
--     不影响功能，如需回收请另行清理 /data 下图片目录）。
--   * TRUNCATE 需要 DROP 权限：业务账号只有 SELECT/INSERT，必须用 root 执行。
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 0. 业务数据（TRUNCATE 同时重置 AUTO_INCREMENT）
-- ---------------------------------------------------------------------
TRUNCATE TABLE item_image;
TRUNCATE TABLE stock_ledger;
TRUNCATE TABLE yahoo_listing;
TRUNCATE TABLE yahoo_import_batch;
TRUNCATE TABLE excel_import_batch;
TRUNCATE TABLE stocktake_scan;
TRUNCATE TABLE stocktake_diff;
TRUNCATE TABLE stocktake;
TRUNCATE TABLE operation_log;
TRUNCATE TABLE client_error;
TRUNCATE TABLE item;
TRUNCATE TABLE seq_item_code;
TRUNCATE TABLE sys_alert;

-- ---------------------------------------------------------------------
-- 1. 字典（甲方自行登记真实会场与价格带）
-- ---------------------------------------------------------------------
DELETE FROM auction_venue;
DELETE FROM price_band;

-- ---------------------------------------------------------------------
-- 2. 压测操作员（保留 admin/demo 等真实账号）
-- ---------------------------------------------------------------------
DELETE FROM sys_user WHERE username LIKE 'bench-op%';

-- ---------------------------------------------------------------------
-- 3. 执行后核对：业务表应为 0；用户与配置应保持原样
-- ---------------------------------------------------------------------
SELECT 'item' AS tbl, COUNT(*) AS rows_left FROM item
UNION ALL SELECT 'item_image', COUNT(*) FROM item_image
UNION ALL SELECT 'stock_ledger', COUNT(*) FROM stock_ledger
UNION ALL SELECT 'operation_log', COUNT(*) FROM operation_log
UNION ALL SELECT 'stocktake', COUNT(*) FROM stocktake
UNION ALL SELECT 'yahoo_import_batch', COUNT(*) FROM yahoo_import_batch
UNION ALL SELECT 'excel_import_batch', COUNT(*) FROM excel_import_batch
UNION ALL SELECT 'auction_venue', COUNT(*) FROM auction_venue
UNION ALL SELECT 'price_band', COUNT(*) FROM price_band
UNION ALL SELECT 'sys_alert', COUNT(*) FROM sys_alert;

SELECT id, username, role, enabled FROM sys_user ORDER BY id;
SELECT COUNT(*) AS sys_setting_rows FROM sys_setting;
