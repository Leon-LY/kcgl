#!/bin/bash
# kcgl 部署后 DB 最小权限逐项自检（docs/01 9.2 交付物）。
# 验证内容：
#   1. 业务账号 kcgl 的 GRANT 集合与 db-init/01-accounts.sh 声明逐条一致（正向断言）；
#   2. kcgl 不含任何 DDL / 流水表 UPDATE/DELETE 权限（负向断言——权限面与
#      「stock_ledger/operation_log 只增不改不删」纪律双保险，验收 9）；
#   3. 迁移账号 kcgl_migrate 存在（app 侧永远不使用它，仅 migrate 一次性容器）。
# 用法：在部署目录（含 .env）执行 ./verify-grants.sh；exit 0=通过。
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
    echo "错误：缺少 .env（参考 .env.example 配置）" >&2
    exit 1
fi
# shellcheck disable=SC1091
source .env

DB_PASSWORD="${DB_PASSWORD:?DB_PASSWORD 未配置}"

# 期望的 kcgl 账号 GRANT 集合（与 db-init/01-accounts.sh 保持同步——两处改动必须成对）
RW_TABLES="sys_user auction_venue year_code price_band item seq_item_code item_image \
yahoo_listing yahoo_import_batch stocktake stocktake_scan stocktake_diff sys_setting \
sys_alert client_error excel_import_batch"
APPEND_ONLY_TABLES="stock_ledger operation_log"

mysql_exec() {
    docker compose exec -T mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD:?}" "$@"
}

fail=0
echo "== 1. 业务账号 kcgl 的正向权限 =="
GRANTS=$(mysql_exec -N -B -e "SHOW GRANTS FOR 'kcgl'@'%';")
for t in $RW_TABLES; do
    if ! grep -qxF "GRANT SELECT, INSERT, UPDATE, DELETE ON \`kcgl\`.\`$t\` TO \`kcgl\`@\`%\`" <<<"$GRANTS"; then
        echo "  [缺失] $t 的 SELECT,INSERT,UPDATE,DELETE"
        fail=1
    fi
done
for t in $APPEND_ONLY_TABLES; do
    if ! grep -qxF "GRANT SELECT, INSERT ON \`kcgl\`.\`$t\` TO \`kcgl\`@\`%\`" <<<"$GRANTS"; then
        echo "  [缺失] $t 的 SELECT,INSERT（不可变流水表）"
        fail=1
    fi
done
if ! grep -qxF "GRANT SELECT ON \`kcgl\`.\`flyway_schema_history\` TO \`kcgl\`@\`%\`" <<<"$GRANTS"; then
    echo "  [缺失] flyway_schema_history 的 SELECT（SystemStatus 显示 DB 版本依赖）"
    fail=1
fi
[ "$fail" -eq 0 ] && echo "  全部 ${#RW_TABLES} 张业务表 + 2 张流水表 + flyway_history 就位"

echo "== 2. 负向断言：kcgl 不得持有 DDL / 流水表写改权 =="
if grep -qiE 'ALTER|DROP|CREATE|TRUNCATE|REFERENCES|INDEX' <<<"$GRANTS"; then
    echo "  [危险] kcgl 持有 DDL 类权限："
    grep -iE 'ALTER|DROP|CREATE|TRUNCATE|REFERENCES|INDEX' <<<"$GRANTS" | sed 's/^/    /'
    fail=1
else
    echo "  无 DDL 权限 ✓"
fi
if grep -qE 'UPDATE|DELETE ON `kcgl`\.`(stock_ledger|operation_log)`' <<<"$GRANTS"; then
    echo "  [危险] 不可变流水表持有 UPDATE/DELETE"
    fail=1
else
    echo "  流水表无 UPDATE/DELETE ✓"
fi
if grep -q 'ALL PRIVILEGES' <<<"$GRANTS"; then
    echo "  [危险] kcgl 持有 ALL PRIVILEGES"
    fail=1
fi

echo "== 3. 迁移账号 =="
if mysql_exec -N -B -e "SELECT 1 FROM mysql.user WHERE user='kcgl_migrate';" | grep -q 1; then
    echo "  kcgl_migrate 存在 ✓（仅 migrate 一次性容器使用）"
else
    echo "  [缺失] kcgl_migrate 账号不存在（迁移容器将无法启动）"
    fail=1
fi

if [ "$fail" -eq 0 ]; then
    echo "== verify-grants：全部通过 =="
else
    echo "== verify-grants：存在不符合项（见上） ==" >&2
fi
exit "$fail"
