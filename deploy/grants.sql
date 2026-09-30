-- =====================================================================
-- kcgl 业务账号表级最小 GRANT（docs/01 八节）——由部署栈 grants 一次性容器
-- 在 Flyway 建表之后执行（幂等：GRANT 天然可重跑，每次部署都跑）。
--
-- 首启顺序：initdb.d 建号（01-accounts.sh，无表依赖）→ migrate 建表
-- → grants 容器执行本文件 → app 起栈。表级 GRANT 依赖表已存在——
-- 曾写在 01-accounts.sh 里首启全静默失败（CREATE USER 成功掩盖批量中止），
-- 本地部署栈预演抓出后拆出（D-078）。
--
-- 表清单与迁移终态对齐（V1 17 张 - V3 退役 year_code + V2 excel_import_batch
-- = 17 张）；迁移新增表后同步在此追加授权行——漏授权会被 app 运行时报错
-- 暴露，verify-grants.sh 的正负断言是部署后的最终口径。
--
-- 口径：15 张业务表四权 + 两张不可变流水表仅 SELECT,INSERT（追加写，
-- 验收 9，权限面与纪律双保险）+ flyway_schema_history 只读（app 禁用
-- Flyway 后 SystemStatusService 仍直查此表显示 DB 版本）。
-- =====================================================================
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.sys_user           TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.auction_venue      TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.price_band         TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.item               TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.seq_item_code      TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.item_image         TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.yahoo_listing      TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.yahoo_import_batch TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.excel_import_batch TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.stocktake          TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.stocktake_scan     TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.stocktake_diff     TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.sys_setting        TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.sys_alert          TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.client_error       TO 'kcgl'@'%';
-- 不可变流水：只增不改不删（验收 9）
GRANT SELECT, INSERT ON kcgl.stock_ledger       TO 'kcgl'@'%';
GRANT SELECT, INSERT ON kcgl.operation_log      TO 'kcgl'@'%';
GRANT SELECT ON kcgl.flyway_schema_history      TO 'kcgl'@'%';
FLUSH PRIVILEGES;
