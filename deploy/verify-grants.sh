#!/bin/bash
# kcgl 部署后 DB 最小权限逐项自检（docs/01 9.2 交付物）。
# 验证内容：
#   1. 业务账号 kcgl 的 GRANT 集合与 grants.sql 声明逐条一致（正向断言）；
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
# shellcheck source=db-cli.sh
source ./db-cli.sh

DB_PASSWORD="${DB_PASSWORD:?DB_PASSWORD 未配置}"
ROOT_PW="${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD 未配置}"

# 期望的 kcgl 账号 GRANT 集合——**直接解析 grants.sql（单一事实源）**，不在脚本内另立
# 清单：D-078 把表级授权拆到 grants.sql 后，两处清单必然漂移（实测——清单里的 year_code
# 是 V3 已退役表，授权实际正确却报「缺失」）。解析失败即硬失败，绝不静默空集通过。
RW_TABLES=$(sed -n 's/^GRANT SELECT, INSERT, UPDATE, DELETE ON kcgl\.\([a-z_]*\) *TO.*/\1/p' grants.sql)
APPEND_ONLY_TABLES=$(sed -n 's/^GRANT SELECT, INSERT ON kcgl\.\([a-z_]*\) *TO.*/\1/p' grants.sql)
if [ -z "$RW_TABLES" ] || [ -z "$APPEND_ONLY_TABLES" ]; then
    echo "错误：无法从 grants.sql 解析授权清单（文件缺失或格式变更）" >&2
    exit 1
fi

mysql_exec() {
    kcgl_mysql "$ROOT_PW" -uroot "$@"
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
[ "$fail" -eq 0 ] && echo "  全部 $(wc -w <<<"$RW_TABLES") 张业务表 + $(wc -w <<<"$APPEND_ONLY_TABLES") 张流水表 + flyway_history 就位"

echo "== 2. 负向断言：kcgl 不得持有 DDL / 流水表写改权 =="
if grep -qiE 'ALTER|DROP|CREATE|TRUNCATE|REFERENCES|INDEX' <<<"$GRANTS"; then
    echo "  [危险] kcgl 持有 DDL 类权限："
    grep -iE 'ALTER|DROP|CREATE|TRUNCATE|REFERENCES|INDEX' <<<"$GRANTS" | sed 's/^/    /'
    fail=1
else
    echo "  无 DDL 权限 ✓"
fi
# 括号必须包住择一：原写法 `UPDATE|DELETE ON ...` 的左支只是裸 UPDATE，
# 任何含 UPDATE 的业务表授权行都命中 → 必然假阳性（实测）
if grep -qE '(UPDATE|DELETE) ON `kcgl`\.`(stock_ledger|operation_log)`' <<<"$GRANTS"; then
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
