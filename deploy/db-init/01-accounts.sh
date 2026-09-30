#!/bin/bash
# kcgl DB 账号创建（docs/01 八节）：MySQL 首次初始化卷时由官方镜像 entrypoint 执行。
# - 不用 MYSQL_USER/MYSQL_PASSWORD 官方变量——它们默认授予库全权，不合最小权限口径。
# - 这里只做「无表依赖」的部分：CREATE USER + kcgl_migrate 的库级授权。
#   ⚠ 表级 GRANT（业务账号 16 表四权 + 流水表只增）必须在 Flyway 建表之后执行
#   （首启顺序=initdb.d 建号 → migrate 建表 → grants 容器跑 grants.sql）——
#   本脚本曾把表级 GRANT 写在这里，首启时表不存在、批量遇错即止全部静默失败
#   （CREATE USER 已成功掩盖了失败），本地部署栈预演抓出（D-078 实录）。
# - 仅首启生效（卷已存在不会重跑）；密码轮换走 ALTER USER，见 docs/runbook.md。
set -euo pipefail

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE USER IF NOT EXISTS 'kcgl'@'%' IDENTIFIED BY '${KCGL_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'kcgl_migrate'@'%' IDENTIFIED BY '${KCGL_MIGRATE_PASSWORD}';
GRANT ALL ON kcgl.* TO 'kcgl_migrate'@'%';
FLUSH PRIVILEGES;
SQL

echo "[kcgl-db-init] 业务/迁移账号已创建，kcgl_migrate 库级授权就位（表级见 grants.sql）"
