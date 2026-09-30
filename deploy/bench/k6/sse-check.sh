#!/bin/bash
# kcgl SSE 并发保持验证（M7-④ 第六门槛：10 用户 SSE，docs/01 §4.2）
#
# 10 个已登录会话并发挂 /api/sync/events 30s，期间录入一件触发广播，
# 断言每个连接都收到**那件**的 ITEM 广播且无一断流退出。
#
# ⚠ 线格式：服务端用 SseEmitter.event().data(json) 发事件，**没有 `event:` 行**——
#   事件类型是 JSON 里的 "type" 字段（见 SseHub.send 与 SseIntegrationTest 同款断言：
#   data 行含 "type":"ITEM"）。本地预演实测：连接与广播都正常，而按 `^event:` 断言
#   则**恒为 0**——门槛永远判失败（断言了一个服务端从不产出的线格式）。
#
# 用法（deploy/ 目录）：
#   ./bench/k6/sse-check.sh [BASE_URL] [PASS]
# 默认 BASE=http://localhost:8082；账号池同 k6 脚本（K6_USERS，默认 bench-op1..4）。
#
# ⚠ 为什么不能用单个账号开 10 个会话：SecurityConfig maximumSessions(8)——第 9 个
#   登录把最早的会话踢掉（expiredSessionStrategy → 401 + SSE 立即关流）。本地预演
#   实测：10 个会话共用一个账号时后 2 个必然被踢，本门槛会「莫名其妙」少连接。
#   10 会话按 K6_USERS 轮转分摊（4 账号 → 每账号 ≤3）。
set -euo pipefail
cd "$(dirname "$0")/../../"

BASE="${1:-http://localhost:8082}"
USER_PASS="${2:-${K6_PASS:-${ADMIN_PASS:-}}}"
DURATION=30
N=10
WORK="$(mktemp -d)"
PIDS=""
# 收尸再删：后台 curl 仍持有 cookie 文件时 rm 报 `Device or resource busy`（MSYS 上必现），
# 且 `set -u` 下 PIDS 未初始化会在 `PIDS="$PIDS $!"` 直接中止脚本（本地预演实测：
# 十会话就绪后刚进并发挂接就死，第六门槛根本没开始测）
cleanup() {
    for pid in $PIDS; do kill "$pid" 2>/dev/null || true; done
    wait 2>/dev/null || true
    rm -rf "$WORK"
}
trap cleanup EXIT

if [ -z "$USER_PASS" ]; then
    echo "错误：未提供密码（第 2 参数或 K6_PASS 环境变量）" >&2
    exit 1
fi

IFS=',' read -r -a USERS <<< "${K6_USERS:-bench-op1,bench-op2,bench-op3,bench-op4}"
[ "${#USERS[@]}" -ge 1 ] || { echo "错误：K6_USERS 为空" >&2; exit 1; }
# 每账号并发会话上限 8（SecurityConfig）：账号数盖不住 N 就直接判失败，
# 而不是让被踢的连接混进断言里
if [ $(( N / ${#USERS[@]} )) -gt 8 ]; then
    echo "错误：$N 个会话 / ${#USERS[@]} 个账号超过单账号 8 会话上限——请扩充 K6_USERS" >&2
    exit 1
fi

echo "== 登录 $N 个会话（账号池：${USERS[*]}） =="
# form-urlencoded（Spring Security formLogin）——发 JSON body 必 401；--data-urlencode
# 顺带处理密码里的 &/+/空格等特殊字符
for i in $(seq 1 "$N"); do
    u="${USERS[$(( (i - 1) % ${#USERS[@]} ))]}"
    code=$(curl -s -o /dev/null -w '%{http_code}' -c "$WORK/cookie.$i" \
        --data-urlencode "username=$u" --data-urlencode "password=$USER_PASS" \
        "$BASE/api/auth/login")
    [ "$code" = 200 ] || { echo "会话 $i（$u）登录失败（$code）" >&2; exit 1; }
done
echo "   $N 个会话就绪"

echo "== 并发挂 SSE（$DURATION s）并触发广播 =="
FAILED=0
for i in $(seq 1 "$N"); do
    curl -sN -b "$WORK/cookie.$i" --max-time "$DURATION" \
        "$BASE/api/sync/events" > "$WORK/sse.$i" 2>/dev/null &
    PIDS="$PIDS $!"
done

sleep 3
# 触发一次广播：录入一件（在途）——事件类型 ITEM_CREATED 会推给全部会话
TODAY=$(date +%F)
PRICE=$((1000 + RANDOM % 90000))
curl -s -c "$WORK/cookie.trigger" \
    --data-urlencode "username=${USERS[0]}" --data-urlencode "password=$USER_PASS" \
    "$BASE/api/auth/login" > /dev/null
# 幂等键在**客户端**生成（web/src/utils/id.ts newClientId，docs/01 7.0）：预览响应里
# 根本没有该字段——原稿 sed 从预览取它恒为空 → 落进「只验证连接保持」分支，第六门槛
# 在真服务器上静默降级（D-079 修的是取值方式，D-080 才发现字段本身不在响应里）。
# 这里自行生成（ASCII，≤36 字符，与 POST 的 clientReqId 约束一致）。
REQ_ID="sse-$(date +%s)-$$-$RANDOM"
# 预览仍打一次：走通「预览→保存」两步录入链路（顺带暴露预览端点故障）
PREVIEW_CODE=$(curl -s -b "$WORK/cookie.trigger" \
    "$BASE/api/item-codes/preview?venueId=1&buyDate=$TODAY&price=$PRICE" \
    | sed -n 's/.*"code":"\([^"]*\)".*/\1/p')
[ -n "$PREVIEW_CODE" ] || echo "   警告：预览未返回管理号（$PREVIEW_CODE），继续保存验证" >&2
CREATE_CODE=$(curl -s -o "$WORK/create.json" -w '%{http_code}' -b "$WORK/cookie.trigger" \
    -H 'Content-Type: application/json' \
    -d "{\"clientReqId\":\"$REQ_ID\",\"venueId\":1,\"buyDate\":\"$TODAY\",\"purchasePrice\":$PRICE,\"warehouse\":1}" \
    "$BASE/api/items")
if [ "$CREATE_CODE" = 200 ]; then
    # 管理号在 data.itemCode（包封 code 是数值 0，不会误匹配）——用它把断言钉到
    # 「收到的是**这一件**的广播」，而不是「收到了某种 ITEM」
    ITEM_CODE=$(sed -n 's/.*"itemCode":"\([^"]*\)".*/\1/p' "$WORK/create.json")
    echo "   已触发广播（录入一件 ${ITEM_CODE:-未知管理号}）"
else
    echo "   错误：录入失败（HTTP $CREATE_CODE）——广播不会发出，本门槛判失败" >&2
    FAILED=1
fi

for pid in $PIDS; do
    wait "$pid" || true   # curl --max-time 到点退出码 28 属预期
done

echo "== 断言：每连接都收到本次触发的 ITEM 广播 =="
for i in $(seq 1 "$N"); do
    # data 行 ≥1 = 至少收到 HELLO（连接真的挂上了）；再要求拿到本次的管理号
    DATA_LINES=$(grep -c '^data:' "$WORK/sse.$i" || true)
    if [ "${DATA_LINES:-0}" -lt 1 ]; then
        echo "   会话 $i：无任何事件行 ✗（连接未建立/已断流）" >&2
        FAILED=1
    elif [ -n "${ITEM_CODE:-}" ] && grep -q -F "$ITEM_CODE" "$WORK/sse.$i"; then
        echo "   会话 $i：收到 $ITEM_CODE 广播（事件行 $DATA_LINES）✓"
    elif [ -z "${ITEM_CODE:-}" ] && grep -q -F '"type":"ITEM"' "$WORK/sse.$i"; then
        echo "   会话 $i：收到 ITEM 广播（管理号未解析出，退化为类型级断言）✓"
    else
        echo "   会话 $i：未收到 $ITEM_CODE 的广播（事件行 $DATA_LINES）✗" >&2
        FAILED=1
    fi
done

if [ "$FAILED" -eq 0 ]; then
    echo "== SSE 门槛通过：$N/$N 连接保持且收到广播 =="
else
    echo "== SSE 门槛未过 ==" >&2
    exit 1
fi
