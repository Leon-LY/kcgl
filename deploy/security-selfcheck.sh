#!/bin/bash
# kcgl 四项安全自检（docs/01 十二节「交付验收」/14 条验收的交付演练入口）。
#   1. 弱值拒启        —— 拿样板口令起 app：必须**拒绝启动**且信息指向被拒的键；
#                         同时确认强值下栈是健康的（否则「拒启」可能是别的原因造成的假绿）。
#   2. DB 权限试探      —— verify-grants.sh（授权清单正向 + DDL/流水表写权负向）
#                         ＋ 用业务账号**真跑**四条越权语句，必须被 MySQL 拒绝；
#                         并跑一条正例（SELECT）证明账号是活的——否则负例全是对口令错。
#   3. HTTPS+安全头抽查 —— 经对外入口实测响应头（SPA 入口与 /api 响应各一次——
#                         nginx 的 add_header 不跨 location 继承，反代段漏挂是常见坑）；
#                         HTTPS 时另断 HSTS，并交叉核对 COOKIE_SECURE 与入口协议是否一致。
#   4. swagger 生产已关 —— /v3/api-docs 等四条路径：直连 app 必须 401/404；经对外入口
#                         必须是 SPA 兜底的 HTML（**不是** OpenAPI JSON——SPA 的
#                         try_files 兜底会把任何未知路径变成 200 HTML，只看状态码会假绿）。
# 用法：在部署目录（含 .env）执行 ./security-selfcheck.sh；exit 0=四项全过。
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

WEB_PORT="${WEB_PORT:-8082}"
BASE_URL="${KCGL_ALLOWED_ORIGINS%%,*}"
[ -n "$BASE_URL" ] || BASE_URL="http://127.0.0.1:$WEB_PORT"

fail=0
note_fail() { fail=1; }

# ---------------------------------------------------------------- 1. 弱值拒启
echo "== 1. 弱值拒启（拿 .env.example 的样板口令起一次 app，必须起不来） =="
APP_IMAGE=$(docker compose config --images 2>/dev/null | grep -E 'kcgl-app|kcgl_app' | head -1 || true)
if [ -z "$APP_IMAGE" ]; then
    echo "  [无法判定] 未从 compose 解析到 app 镜像（docker compose config --images）" >&2
    note_fail
else
    # 一次性容器、不接网络：守卫是 BeanFactoryPostProcessor，在任何 bean（含数据源/
    # Flyway）实例化前执行——弱值必须在**连数据库之前**就被拦下，故无网络也应拒启。
    set +e
    WEAK_OUT=$(timeout 90 docker run --rm \
        -e DB_HOST=mysql -e DB_PORT=3306 -e DB_NAME=kcgl -e DB_USER=kcgl \
        -e DB_PASSWORD=change-me-app \
        -e SPRING_FLYWAY_ENABLED=false \
        -e KCGL_ALLOWED_ORIGINS=http://change-me:8082 \
        "$APP_IMAGE" 2>&1)
    WEAK_EXIT=$?
    set -e
    if [ "$WEAK_EXIT" -eq 0 ]; then
        echo "  [危险] 样板口令下 app 竟然启动成功（exit 0）——弱值拒启失效" >&2
        note_fail
    elif grep -q '弱值拒启' <<<"$WEAK_OUT" && grep -q 'spring.datasource.password' <<<"$WEAK_OUT"; then
        echo "  样板口令被拒（exit $WEAK_EXIT），信息指向 spring.datasource.password ✓"
    else
        # 起不来但**理由不是弱值**：连接失败/镜像坏都长这样，不能算过
        echo "  [危险] app 未启动但原因不是弱值拒启——须人工确认（末 5 行）：" >&2
        tail -5 <<<"$WEAK_OUT" | sed 's/^/    /' >&2
        note_fail
    fi
fi

# 正例：强值下真栈健康（否则上面的「拒启」可能是环境坏导致的无差别失败）。
# 容器经 compose service 名解析——不硬编码 kcgl-app（改名/`-p` 第二套部署下会误报）。
APP_CID=$(docker compose ps -q app 2>/dev/null | head -1)
APP_HEALTH=$(docker inspect --format '{{.State.Health.Status}}' "${APP_CID:-kcgl-app}" 2>/dev/null || echo missing)
if [ "$APP_HEALTH" = healthy ]; then
    echo "  正例：强值下 app 容器健康（拒启不是无差别失败）✓"
else
    echo "  [危险] app 健康状态=$APP_HEALTH（应为 healthy）" >&2
    note_fail
fi

# ---------------------------------------------------------------- 2. DB 权限试探
echo "== 2. DB 权限试探（业务账号真跑越权语句，必须被拒） =="
if ./verify-grants.sh > /tmp/kcgl-verify-grants.out 2>&1; then
    echo "  verify-grants.sh 全过（授权清单正向 + DDL/流水表写权负向）✓"
else
    echo "  [危险] verify-grants.sh 未通过：" >&2
    sed 's/^/    /' /tmp/kcgl-verify-grants.out >&2
    note_fail
fi

# 负例四条 + 正例一条：口令取自 .env（与 app 同一业务账号），经容器内客户端执行。
# 口令经 db-cli.sh 走 stdin——**曾经用 `-p"$DB_PASSWORD"` 传参**，在 Git Bash 下被
# MSYS 当路径改写（32→45 字符），于是正例失败、四条负例却条条「被拒 ✓」假绿（D-088）。
mysql_probe() {  # $1=SQL；输出行，退出码即 mysql 退出码
    kcgl_mysql "${DB_PASSWORD:?}" -ukcgl \
        --default-character-set=utf8mb4 -N -B kcgl -e "$1" 2>/dev/null
}
probe_must_fail() {  # $1=描述 $2=SQL
    if mysql_probe "$2" >/dev/null 2>&1; then
        echo "  [危险] $1：业务账号执行成功（应为拒绝）" >&2
        note_fail
    else
        echo "  $1：被拒 ✓"
    fi
}
if [ "$(mysql_probe 'SELECT COUNT(*) FROM item' | tr -d '[:space:]')" != "" ]; then
    echo "  正例：业务账号可读业务表（负例非「口令错」所致的假绿）✓"
else
    echo "  [危险] 正例失败：业务账号连 SELECT 都不通——下面四条负例无意义" >&2
    note_fail
fi
probe_must_fail "CREATE TABLE（DDL）" "CREATE TABLE kcgl_probe_security (id INT)"
probe_must_fail "DROP TABLE（DDL）" "DROP TABLE IF EXISTS kcgl_probe_security"
# 列名必须真存在：列不存在时报 1054（语法/解析层），**在权限检查之前**就失败——
# 那样负例会以「被拒」的样子假绿（实测口径：privilege 检查先于列解析，但列名写错
# 会让断言失去意义）。qty_change 为 V1 流水表实际列名（无 qty 列）。
probe_must_fail "UPDATE stock_ledger（不可变流水）" "UPDATE stock_ledger SET qty_change = 0 WHERE id = -1"
probe_must_fail "DELETE FROM operation_log（不可变流水）" "DELETE FROM operation_log WHERE id = -1"

# ---------------------------------------------------------------- 3. HTTPS+安全头
echo "== 3. HTTPS+安全头抽查（对外入口 $BASE_URL） =="
check_headers() {  # $1=描述 $2=URL
    local headers
    headers=$(curl -sS -D - -o /dev/null --max-time 15 "$2" 2>/dev/null || true)
    if [ -z "$headers" ]; then
        echo "  [危险] $1：请求失败（$2 不可达）" >&2
        note_fail
        return
    fi
    local missing=""
    grep -qi '^X-Content-Type-Options: *nosniff' <<<"$headers" || missing="$missing nosniff"
    grep -qi '^X-Frame-Options: *DENY' <<<"$headers" || missing="$missing X-Frame-Options"
    grep -qi '^Referrer-Policy: *same-origin' <<<"$headers" || missing="$missing Referrer-Policy"
    if [ -n "$missing" ]; then
        echo "  [危险] $1：缺响应头$missing" >&2
        note_fail
    else
        echo "  $1：nosniff / X-Frame-Options / Referrer-Policy 齐备 ✓"
    fi
}
check_headers "SPA 入口" "$BASE_URL/"
# 未认证的 API 响应（401 JSON）——反代段若不 inherit 安全头，这里立刻现形
check_headers "API 响应" "$BASE_URL/api/items?page=1&size=1"

case "$BASE_URL" in
    https://*)
        HSTS=$(curl -sS -D - -o /dev/null --max-time 15 "$BASE_URL/" 2>/dev/null || true)
        if grep -qi '^Strict-Transport-Security:' <<<"$HSTS"; then
            echo "  HSTS 已下发 ✓"
        else
            echo "  [危险] HTTPS 入口未下发 Strict-Transport-Security" >&2
            note_fail
        fi
        ;;
    *)
        echo "  HSTS：SKIP（当前入口为 HTTP——备案域名/TLS 就绪后本项转为实测）"
        if [ "${COOKIE_SECURE:-false}" = "true" ]; then
            echo "  [危险] COOKIE_SECURE=true 但对外入口是 HTTP：Secure cookie 发不出去，登录必失败" >&2
            note_fail
        else
            echo "  协议/COOKIE_SECURE 一致（HTTP 部署 + COOKIE_SECURE=false）✓"
        fi
        ;;
esac

# ---------------------------------------------------------------- 4. swagger 生产已关
echo "== 4. swagger 生产已关（docs/01 八节：生产不暴露 API 文档面） =="
for path in /v3/api-docs /v3/api-docs/swagger-config /swagger-ui/index.html /swagger-ui.html; do
    # 直连 app：401（未认证）或 404（无此端点）都算未暴露；200 且是 OpenAPI JSON = 暴露。
    # 整条远程命令走 sh -c 单参数：Git Bash 会把裸 `/dev/null`、`/v3/...` 形态参数改写成
    # Windows 路径（D-080 第 12 条同族），包进引号命令串后 MSYS 不再改写。
    code=$(docker compose exec -T app sh -c \
        "curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://127.0.0.1:8080$path" 2>/dev/null || echo 000)
    if [ "$code" = 401 ] || [ "$code" = 404 ]; then
        echo "  app 直连 $path → $code（未暴露）✓"
    else
        echo "  [危险] app 直连 $path → $code（预期 401/404）" >&2
        note_fail
    fi
    # 对外入口：SPA 兜底会让任何未知路径返回 200 HTML——**只看状态码会假绿**，
    # 真正要断的是「响应不是 OpenAPI 文档」。nginx 只反代 /api/，故此处应命不中 app。
    body=$(curl -sS --max-time 15 "$BASE_URL$path" 2>/dev/null || true)
    if grep -q '"openapi"' <<<"$body" || grep -q '"swagger"' <<<"$body"; then
        echo "  [危险] 对外入口 $path 返回了 API 文档内容（已暴露）" >&2
        note_fail
    else
        echo "  对外入口 $path → 非 API 文档内容（SPA 兜底 HTML 或 404）✓"
    fi
done

if [ "$fail" -eq 0 ]; then
    echo "== security-selfcheck：四项全过 =="
else
    echo "== security-selfcheck：存在不符合项（见上） ==" >&2
fi
exit "$fail"
