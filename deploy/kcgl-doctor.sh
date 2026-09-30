#!/bin/bash
# kcgl 每日巡检（docs/01 9.2 交付物）——四项检查，结果落 doctor-status.json：
#   1. 容器健康：mysql/app/web 三容器 running 且 healthy（migrate 一次性容器不在列）
#   2. 磁盘水位：根盘与备份盘使用率（阈值 80%，与 app 自检/backup 门禁同口径）
#   3. 证书剩余天数：TLS 未启用则 SKIP；否则证书 30 天内到期即 FAIL（certbot 续期
#      失效的最常见后果）。证书路径经 KCGL_TLS_CERT 配置（HTTPS 部署时见 deployment.md）
#   4. 备份新鲜度：backup-status.json 的 lastSuccessAt 距今 >25h 即 FAIL
#      （03:30 cron + 25h 容忍跨日边界与一次失败重试窗）
# cron（宿主本地时区，每日 04:45——在 app 自检 job 04:17 之后）。
# 不用 CRON_TZ：Ubuntu 的 cron（vixie 系）不支持该变量，写了会被静默忽略；
# 需要每小时点检时改 7 * * * *（deployment.md §7）。
#   45 4 * * * root cd /opt/kcgl && ./kcgl-doctor.sh >> /var/log/kcgl-doctor.log 2>&1
# 用法：./kcgl-doctor.sh   （在部署目录执行；exit 0=全绿/含 SKIP）
set -uo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
    echo "错误：缺少 .env（参考 .env.example 配置）" >&2
    exit 1
fi
# shellcheck disable=SC1091
source .env

BACKUP_DIR="${KCGL_BACKUP_DIR:-/opt/kcgl/backup}"
TLS_CERT="${KCGL_TLS_CERT:-}"
DISK_WARN_PERCENT=80
STATUS_FILE="$BACKUP_DIR/doctor-status.json"

overall=0
declare -a lines=()

pass() { lines+=("PASS|$1"); }
fail() { lines+=("FAIL|$1"); overall=1; }
skip() { lines+=("SKIP|$1"); }

# ---------- 1. 容器健康 ----------
# 容器名经 `docker compose ps -q <service>` 解析，**不硬编码 kcgl-app**：
# 甲方可能给容器改名、或用 `-p <project>` 在同机跑第二套（干净主机演练即如此），
# 硬编码名会让点检在这种部署下整片误报 FAIL。service 名由本目录 compose 定义。
for c in mysql app web; do
    cid=$(docker compose ps -q "$c" 2>/dev/null | head -1)
    if [ -z "$cid" ]; then
        fail "容器 $c：未找到（docker compose ps -a 排查）"
        continue
    fi
    cname=$(docker inspect -f '{{.Name}}' "$cid" 2>/dev/null | sed 's|^/||')
    state=$(docker inspect -f '{{.State.Status}}' "$cid" 2>/dev/null || echo missing)
    health=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}n/a{{end}}' "$cid" 2>/dev/null || echo n/a)
    if [ "$state" = running ] && { [ "$health" = healthy ] || [ "$health" = n/a ]; }; then
        pass "容器 ${cname:-$c}：running/${health}"
    else
        fail "容器 ${cname:-$c}：${state}/${health}（docker compose logs $c 排查）"
    fi
done

# ---------- 2. 磁盘水位 ----------
for path in / "$BACKUP_DIR"; do
    usage=$(df -P "$path" 2>/dev/null | awk 'NR==2 {gsub(/%/,""); print $5}')
    if [ -z "$usage" ]; then
        skip "磁盘 $path：利用不可"
    elif [ "$usage" -gt "$DISK_WARN_PERCENT" ]; then
        fail "磁盘 $path：${usage}% > ${DISK_WARN_PERCENT}%（清理备份/日志或扩容）"
    else
        pass "磁盘 $path：${usage}%"
    fi
done

# ---------- 3. 证书剩余天数 ----------
if [ -z "$TLS_CERT" ]; then
    skip "证书：未启用 TLS（HTTP 部署）"
elif [ ! -f "$TLS_CERT" ]; then
    fail "证书：$TLS_CERT 不存在但 KCGL_TLS_CERT 已配置"
elif openssl x509 -checkend $((30 * 86400)) -noout -in "$TLS_CERT" >/dev/null 2>&1; then
    pass "证书：剩余 >30 天"
else
    fail "证书：30 天内到期（检查 certbot 续期或手动换证）"
fi

# ---------- 4. 备份新鲜度 ----------
STATUS="$BACKUP_DIR/backup-status.json"
if [ ! -f "$STATUS" ]; then
    fail "备份：从未运行（backup-status.json 不存在——配置 cron）"
else
    last_ok=$(sed -n 's/.*"lastSuccessAt":"\([^"]*\)".*/\1/p' "$STATUS")
    if [ -z "$last_ok" ]; then
        fail "备份：尚无成功记录（查 backup-status.json 的 lastErrorAt）"
    else
        last_ep=$(date -d "$last_ok" +%s 2>/dev/null || echo 0)
        age_h=$(( ($(date +%s) - last_ep) / 3600 ))
        if [ "$age_h" -gt 25 ]; then
            fail "备份：最近成功距今 ${age_h}h > 25h（查 /var/log/kcgl-backup.log）"
        else
            pass "备份：${age_h}h 前成功"
        fi
    fi
fi

# ---------- 输出 ----------
echo "== kcgl-doctor $(TZ=Asia/Tokyo date '+%F %T %Z') =="
for l in "${lines[@]}"; do
    echo "  ${l%%|*}  ${l#*|}"
done
[ "$overall" -eq 0 ] && echo "== 全部通过（或 SKIP） ==" || echo "== 存在 FAIL 项 ==" >&2

# 状态文件（供管理后台/诊断导出引用；格式与 backup-status.json 同族）
mkdir -p "$BACKUP_DIR"
checks_str=""
for l in "${lines[@]}"; do
    checks_str+="${l%%|*}: ${l#*|}; "
done
printf '{"ranAt":"%s","ok":%s,"checks":"%s"}\n' \
    "$(date -Is)" "$([ "$overall" -eq 0 ] && echo true || echo false)" \
    "${checks_str%; }" \
    > "$STATUS_FILE" 2>/dev/null || true
chmod 600 "$STATUS_FILE" 2>/dev/null || true
exit "$overall"
