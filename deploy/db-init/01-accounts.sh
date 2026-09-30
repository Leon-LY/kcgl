#!/bin/bash
# kcgl DB 账号分离（docs/01 八节）：MySQL 首次初始化卷时由官方镜像 entrypoint 执行。
# - 不用 MYSQL_USER/MYSQL_PASSWORD 官方变量——它们默认授予库全权，不合最小权限口径。
# - 业务账号 kcgl：16 张业务表 SELECT/INSERT/UPDATE/DELETE；两张不可变流水表
#   stock_ledger/operation_log 仅 SELECT/INSERT（追加写）；flyway_schema_history 仅
#   SELECT（app 禁用 Flyway 后 SystemStatusService 仍直查此表显示 DB 版本）。
# - 迁移账号 kcgl_migrate：库全权（DDL），仅 migrate 一次性容器使用。
# - 仅首启生效（卷已存在不会重跑）；密码轮换走 ALTER USER，见 docs/runbook.md。
set -euo pipefail

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE USER IF NOT EXISTS 'kcgl'@'%' IDENTIFIED BY '${KCGL_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'kcgl_migrate'@'%' IDENTIFIED BY '${KCGL_MIGRATE_PASSWORD}';

GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.sys_user          TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.auction_venue     TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.year_code         TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.price_band        TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.item              TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.seq_item_code     TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.item_image        TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.yahoo_listing     TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.yahoo_import_batch TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.stocktake         TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.stocktake_scan    TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.stocktake_diff    TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.sys_setting       TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.sys_alert         TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.client_error      TO 'kcgl'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl.excel_import_batch TO 'kcgl'@'%';
-- 不可变流水：只增不改不删（验收 9），权限面与纪律双保险
GRANT SELECT, INSERT ON kcgl.stock_ledger   TO 'kcgl'@'%';
GRANT SELECT, INSERT ON kcgl.operation_log  TO 'kcgl'@'%';
GRANT SELECT ON kcgl.flyway_schema_history  TO 'kcgl'@'%';

GRANT ALL ON kcgl.* TO 'kcgl_migrate'@'%';
FLUSH PRIVILEGES;
SQL

echo "[kcgl-db-init] 业务/迁移账号与最小 GRANT 已就位"
