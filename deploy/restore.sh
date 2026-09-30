#!/bin/bash
# kcgl 恢复与演练（docs/01 9.2）。
#
# 两种模式：
#   ./restore.sh --verify <db.sql.zst>
#       演练（不碰主库、不停服）：导入到临时库 kcgl_verify → 跑对账不变量
#       断言 → DROP 临时库。触发时点：发版演练 / backup.sh 改动后 / 大版本升级前。
#       不变量 SQL 与 server 侧 LedgerConsistencyService.check() 同口径
#       （逐件头寸向量比对——相互抵消的仓级漂移只有逐件能抓）+ 计数器倒退 +
#       桶行丢失；两处口径如需变更必须成对修改。
#   ./restore.sh --restore <db.sql.zst> [--yes]
#       真实恢复（危险）：停 app → 清空并导入主库 kcgl → 起 app。
#       默认交互确认；分级回滚语义见 docs/deployment.md（R10：回旧镜像≠回旧 schema，
#       不兼容变更须预备份恢复并明示丢失窗口）。
# 用法前提：在部署目录执行（读 .env）；宿主需 docker 与 zstd。
set -euo pipefail
cd "$(dirname "$0")"

usage() { grep '^#   \|^#       ' "$0" | sed 's/^# \{0,7\}//'; exit 2; }

MODE=""
DUMP=""
ASSUME_YES=0
for arg in "$@"; do
    case "$arg" in
        --verify) MODE=verify ;;
        --restore) MODE=restore ;;
        --yes) ASSUME_YES=1 ;;
        -h|--help) usage ;;
        -*) echo "未知参数：$arg" >&2; usage ;;
        *) DUMP="$arg" ;;
    esac
done
[ -n "$DUMP" ] && [ -n "$MODE" ] || usage   # 必须显式 --verify 或 --restore，防误入错误模式
[ -f "$DUMP" ] || { echo "错误：找不到备份文件 $DUMP" >&2; exit 1; }

if [ ! -f .env ]; then
    echo "错误：缺少 .env（参考 .env.example 配置）" >&2
    exit 1
fi
# shellcheck disable=SC1091
source .env
# shellcheck source=db-cli.sh
source ./db-cli.sh
ROOT_PW="${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD 未配置}"

mysql_root() { kcgl_mysql "$ROOT_PW" -uroot "$@"; }

# zstd 是**宿主**命令（它的输出经管道喂给容器内 mysql），因此要喂宿主原生路径：
# Windows 版 zstd 不认 Git Bash 习惯的 `/c/Users/...`（报 `can't stat ...: No such
# file or directory`，而同一路径上面的 `[ -f ]` 检查却通过——故障点与检查点分离，
# 表现为「文件明明在，导入却说备份不可用」，本地预演实测）。有 cygpath 就转；服务器
# 是 Linux、无 cygpath，原样使用。
DUMP_RUN="$DUMP"
command -v cygpath >/dev/null 2>&1 && DUMP_RUN="$(cygpath -w "$DUMP")"
mysql_root_db() {  # $1=库名，其余参数透传 mysql
    local db="$1"; shift
    kcgl_mysql "$ROOT_PW" -uroot "$db" "$@"
}

VERIFY_DB=kcgl_verify

# 逐件头寸向量断言（LedgerConsistencyService 同口径）：
#   在库件（stock_status=1 未删未废）期望头寸 = 恰好其所在仓 +1、其余仓 0；
#   非在库/已删/已废件期望全 0；流水指向 item 表不存在的件（幽灵件）计为漂移。
DRIFT_SQL="SELECT t.item_id, t.p1, t.p2, i.item_code, i.warehouse, i.stock_status, i.voided, i.deleted
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
)"

# 计数器倒退断言：cur_seq < 已用最大 seq_no → 下一号必撞 uk（D-0xx 防线前兆）。
# 桶键=（venue_id, month）——V3 去年代号后无 year 列；JOIN 限定 cur_prefix
# 只比当前前缀下的 MAX(seq_no)（进位后历史前缀的 1..99 与新前缀无关）。
SEQ_BACKWARD_SQL="SELECT s.venue_id, s.month, s.cur_prefix, s.cur_seq, MAX(i.seq_no) AS max_seq
FROM seq_item_code s JOIN item i
  ON i.venue_id = s.venue_id AND i.buy_month = s.month AND i.seq_prefix = s.cur_prefix
GROUP BY s.venue_id, s.month, s.cur_prefix, s.cur_seq
HAVING s.cur_seq < MAX(i.seq_no)"

# 桶行丢失断言：item 有该桶的件但 seq_item_code 无桶行 → 生成路径失去依据。
# 仅按桶键匹配（venue_id, month）——若按前缀匹配，桶进位后（cur_prefix=C）
# 历史前缀（A/B）件会误报丢失。
MISSING_BUCKET_SQL="SELECT DISTINCT i.venue_id, i.buy_month
FROM item i LEFT JOIN seq_item_code s
  ON s.venue_id = i.venue_id AND s.month = i.buy_month
WHERE s.id IS NULL"

run_assertions() {  # $1=目标库名
    local db="$1" bad=0 out
    echo "== 对账不变量断言（与 LedgerConsistencyService.check() 同口径） =="

    out=$(mysql_root_db "$db" -N -B -e "$DRIFT_SQL") || { echo "SQL 执行失败（逐件头寸）" >&2; return 1; }
    if [ -n "$out" ]; then echo "  [FAIL] 逐件头寸漂移："; echo "$out" | head -20 | sed 's/^/    /'; bad=1
    else echo "  逐件头寸向量一致 ✓"; fi

    out=$(mysql_root_db "$db" -N -B -e "$SEQ_BACKWARD_SQL") || { echo "SQL 执行失败（计数器倒退）" >&2; return 1; }
    if [ -n "$out" ]; then echo "  [FAIL] 计数器倒退（cur_seq < MAX(seq_no)）："; echo "$out" | head -20 | sed 's/^/    /'; bad=1
    else echo "  计数器无倒退 ✓"; fi

    out=$(mysql_root_db "$db" -N -B -e "$MISSING_BUCKET_SQL") || { echo "SQL 执行失败（桶丢失）" >&2; return 1; }
    if [ -n "$out" ]; then echo "  [FAIL] 计数器桶行丢失："; echo "$out" | head -20 | sed 's/^/    /'; bad=1
    else echo "  无桶行丢失 ✓"; fi

    echo "-- 规模快照 --"
    mysql_root_db "$db" -e "SELECT (SELECT COUNT(*) FROM item) items, (SELECT COUNT(*) FROM stock_ledger) ledgers, (SELECT COUNT(*) FROM operation_log) op_logs;"
    return "$bad"
}

import_to() {  # $1=目标库名（须已存在）
    # stdin 已被 dump 流占用 → 由 kcgl_mysql_import 把口令行前置在流首
    zstd -dc "$DUMP_RUN" | kcgl_mysql_import "$ROOT_PW" "$1"
}

# ---------------------------------------------------------------- 演练模式
if [ "$MODE" = verify ]; then
    echo "== 演练：恢复 $DUMP → 临时库 $VERIFY_DB（主库不受影响） =="
    mysql_root -e "DROP DATABASE IF EXISTS \`$VERIFY_DB\`; CREATE DATABASE \`$VERIFY_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    if ! import_to "$VERIFY_DB"; then
        echo "错误：导入失败——备份不可用" >&2
        mysql_root -e "DROP DATABASE IF EXISTS \`$VERIFY_DB\`;"
        exit 1
    fi
    if run_assertions "$VERIFY_DB"; then
        mysql_root -e "DROP DATABASE IF EXISTS \`$VERIFY_DB\`;"
        echo "== 演练通过：备份可恢复且数据一致，临时库已丢弃 =="
    else
        mysql_root -e "DROP DATABASE IF EXISTS \`$VERIFY_DB\`;"
        echo "== 演练失败：备份可导入但存在不变量违例（保留输出供排查；临时库已丢弃） ==" >&2
        exit 1
    fi
    exit 0
fi

# ---------------------------------------------------------------- 真实恢复
echo "!! 真实恢复将停服并清空主库 kcgl，用 $DUMP 覆盖。"
echo "!! 确认已在变更窗口内，且该备份是预期版本（回滚语义见 docs/deployment.md R10）。"
if [ "$ASSUME_YES" -ne 1 ]; then
    read -r -p "输入 yes 继续： " answer
    [ "$answer" = yes ] || { echo "已中止"; exit 1; }
fi

echo "== 停 app（web 保留：登录页 502 属预期，恢复窗口内） =="
docker compose stop app

echo "== 清空并导入主库 =="
mysql_root -e "DROP DATABASE IF EXISTS kcgl; CREATE DATABASE kcgl CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
import_to kcgl

echo "== 起并校验 =="
docker compose start app
for i in $(seq 1 30); do
    if docker compose exec -T app curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
        echo "app healthy。建议立即：浏览器登录验证 + ./kcgl-doctor.sh 全绿"
        exit 0
    fi
    sleep 2
done
echo "错误：app 起后 60s 未 healthy——查 docker compose logs app" >&2
exit 1
