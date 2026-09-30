#!/bin/bash
# kcgl 压测图片落盘（M7-④，D-078）——seed-bench.sql 之后跑。
#
# 从 item_image 表读 (stored_path, thumb_path) 清单，把两枚占位母本
# JPEG 硬链接到 /data/images 对应路径（kcgl-app-data 卷）：
#   - 硬链接零拷贝：25 万文件名只占 inode，不占数据块——既制造真实
#     的目录层级/文件数压力（图片直出/列表缩略图/Nginx alias 遍历），
#     又不撑爆共享测试机磁盘（勘察 D-077：磁盘 39%）
#   - 文件内容统一不影响压测目标（静态资产吞吐与图片内容无关）
#   - 与 ImageStore 约定同构：{yyyy}/{MM}/{uuid}.jpg + 缩略图
#
# 幂等：ln -f 覆盖式重链；母本已存在则跳过生成。
# 用法：./bench-images.sh（deploy/ 目录下，读 .env；宿主需 docker）
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
    echo "错误：缺少 .env（参考 .env.example 配置）" >&2
    exit 1
fi
# shellcheck disable=SC1091
source .env
ROOT_PW="${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD 未配置}"
# shellcheck source=db-cli.sh
source ./db-cli.sh          # 口令只经容器 stdin（本脚本曾用 -p 传参，D-088 同族）

FIXTURES="bench/fixtures"

# ---------------------------------------------------------------- 镜像与卷名（口径与 backup.sh 一致）
# ① 工具镜像用**随包交付**的 kcgl-tools（debian-slim），不从公网拉 alpine：落盘是 M7-④
#    的必经路径，而实测该主机上 Docker Hub 取镜像会超时（同一时段 `docker pull` 报
#    deadline exceeded）。与 D-094 同族——关键路径的依赖必须随栈到位，不能指望运行期联网。
#    debian-slim 已含本步骤用到的 sh/awk/sort/ln/find/wc。
# ② 卷名从 **app 容器实挂载**解析，不拼 `kcgl_kcgl-app-data`：`-p`/`COMPOSE_PROJECT_NAME`
#    会改变卷名（演练栈即如此），拼名字会把硬链接写进**另一套栈的卷**，而校验段也会因
#    挂错卷数出 0 个文件——静默指向错数据比报错更危险（D-087 同族）。
TOOLS_IMAGE="ghcr.io/leon-ly/kcgl-tools:${KCGL_VERSION:-latest}"
APP_CID=$(docker compose ps -q app 2>/dev/null | head -1)
[ -n "$APP_CID" ] || { echo "错误：app 容器未运行，无法解析图片卷（先 docker compose up -d）" >&2; exit 1; }
IMAGE_VOLUME=$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}' "$APP_CID")
[ -n "$IMAGE_VOLUME" ] || { echo "错误：未能从 app 容器解析 /data 卷（栈是否健康？）" >&2; exit 1; }
echo "   工具镜像：$TOOLS_IMAGE"; echo "   图片卷：$IMAGE_VOLUME"

# ---------------------------------------------------------------- 宿主路径 → docker
# docker 的 -v 宿主源必须是**宿主原生路径**：在 Git Bash（MSYS）里直接给 POSIX 路径，
# MSYS 会把它连同后面的容器路径一起改写，`.../bench/fixtures:/fix:ro` 变成 Docker
# 认不出的东西——Docker 不报错，而是**静默新建一个名为 `fixtures;D` 的垃圾目录**当源，
# 容器里既没有 /fix，直到 cp 母本才炸：`cp: can't stat '/fix/placeholder.jpg'`
# （本地预演实测；同时留下 fixtures;D 空壳，已删）。服务器是 Linux，cygpath 不存在，
# 故按「有则转、无则原样」处理，一套脚本两处平台通用。
host_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$(cd "$1" && pwd)"
    else
        (cd "$1" && pwd)
    fi
}

# ---------------------------------------------------------------- 母本（幂等生成）
# 原图 800x600 / 缩略 200x150；生成一次入 repo，服务器与干净主机直接复用
if [ ! -f "$FIXTURES/placeholder.jpg" ] || [ ! -f "$FIXTURES/placeholder-thumb.jpg" ]; then
    echo "== 生成占位母本 JPEG（alpine+imagemagick 一次性容器） =="
    mkdir -p "$FIXTURES"
    # imagemagick-jpeg 必须显式装：alpine 把 JPEG 编解码拆成子包，只装主包时
    # `magick` 无 JPEG 写委托，报错还指向输入伪格式而非 JPEG（实测「no encode
    # delegate for this image format `GRADIENT'」——名字误导，极易误判成渐变语法问题）
    docker run --rm -v "$(host_path "$FIXTURES"):/out" alpine:3.20 sh -c '
        apk add -q --no-cache imagemagick imagemagick-jpeg >/dev/null &&
        magick -size 800x600 gradient:"#2062a6-#0a3d63" -quality 72 /out/placeholder.jpg &&
        magick -size 200x150 gradient:"#2062a6-#0a3d63" -quality 72 /out/placeholder-thumb.jpg
    ' || { echo "错误：母本生成失败（需外网拉 alpine/apk）" >&2; exit 1; }
fi

# ---------------------------------------------------------------- 路径清单（走管道，不落宿主文件）
echo "== 图片行数（MySQL 侧计数） =="
TOTAL=$(kcgl_mysql "$ROOT_PW" -uroot -N -B kcgl -e "SELECT COUNT(*) FROM item_image")
if [ "${TOTAL:-0}" -eq 0 ]; then
    echo "错误：item_image 为空——先跑 seed-bench.sql" >&2
    exit 1
fi
echo "   图片行数：$TOTAL（落盘 $((TOTAL * 2)) 个文件名）"

# ---------------------------------------------------------------- 硬链接（容器内执行，直写卷）
# 清单不落宿主临时文件：Git Bash 的 /tmp 是 MSYS 私有路径，Docker Desktop 解析
# 不到宿主 temp（静默建成空目录 → 容器里 awk 报 `Is a directory`），而卷挂载又
# 必须带 MSYS_NO_PATHCONV=1（否则 `kcgl_kcgl-app-data:/data` 被当成盘符路径改写）
# ——两者叠加使宿主中转文件必然踩雷（本地预演实测，D-080）。改为管道直喂 stdin。
echo "== 硬链接落盘（$IMAGE_VOLUME 卷 → /data/images） =="
kcgl_mysql "$ROOT_PW" -uroot -N -B kcgl \
    -e "SELECT stored_path, thumb_path FROM item_image" \
  | docker run --rm -i \
        -v "$IMAGE_VOLUME":/data \
        -v "$(host_path "$FIXTURES")":/fix:ro \
        "$TOOLS_IMAGE" sh -c '
            set -e
            # 母本先拷进卷内再链：/fix 是宿主 bind 挂载，与卷**不保证同设备**——
            # 硬链接只能同设备（Docker Desktop 下必然 Cross-device link；服务器上
            # Docker 数据目录独立分区时同样会炸，且报错只指第一行、极难反推）。
            # 母本落 /data/.bench-fixtures（ImageStore 只服务 /data/images，
            # 备份快照亦只收 /data/images，卷内多这一目录无副作用）
            cat > /tmp/list.tsv
            # 先清旧目录再链：image 路径含 UUID()（每轮重灌都是新名），旧文件在
            # 重灌后已不被任何行引用——不清则计数校验失真（第二次跑的计数是两轮
            # 之和）且共享测试机的 inode 白占一份全量。清理成本=重新硬链约 1.5 分钟
            rm -rf /data/images /data/.bench-fixtures
            mkdir -p /data/.bench-fixtures
            # 目录集一次建齐（yyyy/MM 只有 ~18 个，远快于逐行 mkdir -p）
            awk -F "\t" "{ print substr(\$1,1,7); print substr(\$2,1,7) }" /tmp/list.tsv \
                | sort -u | while read -r d; do mkdir -p "/data/images/$d"; done
            # 母本池：single inode 的硬链接数有文件系统上限（ext4 65k，部分 fs 32k），
            # 12.5 万路径全指一枚母本必撞 `Too many links`（实测）——按 PER_POOL 上限
            # 分池，每池复制一份母本（池数×数百字节，仍近零空间）；
            # 路径与池按位置轮转绑定，分布均匀
            PER_POOL=20000
            POOL=$(( ($(wc -l < /tmp/list.tsv) + PER_POOL - 1) / PER_POOL ))
            i=1
            while [ "$i" -le "$POOL" ]; do
                cp -f /fix/placeholder.jpg       "/data/.bench-fixtures/img.$i"
                cp -f /fix/placeholder-thumb.jpg "/data/.bench-fixtures/thumb.$i"
                i=$((i + 1))
            done
            # 第 $1 列逐行硬链到 $2 池（每 POOL 个路径轮换一枚母本）
            link_column() {
                col="$1"; pool="$2"
                set -- "$pool".*                   # 池文件（glob 展开数量=POOL）
                n=0
                awk -F "\t" -v c="$col" "{ print \$c }" /tmp/list.tsv | \
                while read -r p; do
                    ln -f "$1" "/data/images/$p"
                    n=$((n + 1))
                    [ $((n % POOL)) -eq 0 ] && { cur="$1"; shift; set -- "$@" "$cur"; }
                done
                true                               # 循环体末次条件判断的退出码不影响整体
            }
            link_column 1 /data/.bench-fixtures/img
            link_column 2 /data/.bench-fixtures/thumb
            echo "   硬链接完成：$(( $(wc -l < /tmp/list.tsv) * 2 ))（母本池 $POOL 份）"
        '

# ---------------------------------------------------------------- 校验
ACTUAL=$(docker run --rm -v "$IMAGE_VOLUME":/data "$TOOLS_IMAGE" \
    sh -c 'find /data/images -type f -name "*.jpg" | wc -l')
if [ "$ACTUAL" -eq "$((TOTAL * 2))" ]; then
    echo "== 校验通过：$ACTUAL 个文件 = 图片行数 × 2 =="
else
    echo "错误：文件数 $ACTUAL ≠ 预期 $((TOTAL * 2))——查容器内 ln 报错" >&2
    exit 1
fi
