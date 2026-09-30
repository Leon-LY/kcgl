#!/bin/bash
# kcgl 每日备份（docs/01 9.2 唯一定义节，勿在他处另立口径）：
#   - DB：mysqldump --single-transaction（InnoDB 一致性快照，不锁业务）
#         --set-gtid-purged=OFF（避免在无 GTID 环境产生恢复阻抗）管道 zstd 压缩；
#   - 图片：rsync --link-dest 硬链快照——未变文件与上一份共享 inode，零额外空间。
#     运维禁令：不得整批重生成缩略图/重编码图片——换 inode 会使当日快照
#     膨胀一份全量（写入 runbook/deployment.md）；
#   - 保留：daily 7 份 + weekly 4 份（周一 03:30 那份同时落 weekly）；
#   - 磁盘水位 >80% 拒跑并写失败状态（app/doctor 据此告警）；
#   - 状态：backup-status.json 供 kcgl-doctor.sh 检查新鲜度、管理后台显示
#     「最近备份成功时间」（app 侧读取为 M7 增量，格式自此冻结）。
# cron（宿主本地时区，每日 03:30——避开自检 job 04:17 与整点潮汐）。
# 不用 CRON_TZ：Ubuntu 的 cron（vixie 系）不支持该变量，写了会被静默忽略。
#   30 3 * * * root cd /opt/kcgl && ./backup.sh >> /var/log/kcgl-backup.log 2>&1
# 用法：./backup.sh   （在部署目录执行，读同目录 .env；宿主需 docker/zstd/rsync）
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

BACKUP_DIR="${KCGL_BACKUP_DIR:-/opt/kcgl/backup}"
# BACKUP_DIR 既是宿主命令（mkdir/ls/du）的路径，也被 docker -v 当**宿主源**——而 Git
# Bash（MSYS）会把 `/c/...` 形态的宿主源改写成容器看不到的东西（Docker 不报错：要么
# 不挂载、要么建个空目录当源，容器里 /backup 直接不存在，rsync 才报文件找不到）。
# 只对 MSYS 盘符形态（/c/...）做转换：服务器上的 /opt/kcgl/backup 同样是绝对路径，
# 但没有 cygpath，且**不能**被当 Git Bash 根目录解析。
case "$BACKUP_DIR" in
    /[a-zA-Z]/*) command -v cygpath >/dev/null 2>&1 && BACKUP_DIR="$(cygpath -w "$BACKUP_DIR")" ;;
esac
ROOT_PW="${MYSQL_ROOT_PASSWORD_BACKUP:-$MYSQL_ROOT_PASSWORD}"
# 样板值不是口令：.env.example 曾把 MYSQL_ROOT_PASSWORD_BACKUP 预置成 change-me-root，
# 甲方照抄不改 → 每晚备份全部 Access denied、静默失败到告警为止（D-090 实测）。
# 空值走上面的回退（正常），样板值直接拒跑。
case "$ROOT_PW" in
    change-me*|"")
        echo "错误：备份管理口令未配置（MYSQL_ROOT_PASSWORD_BACKUP 留空即可，脚本会沿用 MYSQL_ROOT_PASSWORD）" >&2
        exit 1
        ;;
esac
DISK_WARN_PERCENT=80

# /data 卷名从 app 容器**实挂载**解析，不按 `<project>_kcgl-app-data` 拼：
# compose 顶层的 `name: kcgl` 会被 -p/COMPOSE_PROJECT_NAME 覆盖（同机第二套栈即如此），
# 拼出来的名字会指向**另一套栈**的卷——rsync 会静默快照到错数据或建空目录，
# 比直接报错更危险（D-087 干净主机演练实测）。容器名/工程名都不参与，故对改名免疫。
resolve_image_volume() {
    local cid
    cid=$(docker compose ps -q app 2>/dev/null | head -1)
    [ -n "$cid" ] || return 1
    docker inspect -f '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}' "$cid" 2>/dev/null
}
IMAGE_VOLUME="$(resolve_image_volume)"
if [ -z "$IMAGE_VOLUME" ]; then
    echo "错误：未能从 app 容器解析 /data 卷（先确认栈已启动：docker compose ps -a）" >&2
    exit 1
fi

TODAY=$(TZ=Asia/Tokyo date +%F)
WEEKDAY=$(TZ=Asia/Tokyo date +%u)                # 1=周一
STATUS_FILE="$BACKUP_DIR/backup-status.json"
umask 077
mkdir -p "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly"

write_status() {  # $1=ok|fail  $2=detail
    local now last_ok=""
    now=$(date -Is)
    [ -f "$STATUS_FILE" ] && last_ok=$(sed -n 's/.*"lastSuccessAt":"\([^"]*\)".*/\1/p' "$STATUS_FILE")
    [ "$1" = ok ] && last_ok="$now"
    printf '{"lastRunAt":"%s","lastSuccessAt":"%s","lastErrorAt":"%s","detail":"%s"}\n' \
        "$now" "${last_ok:-}" "$([ "$1" = ok ] && echo '' || echo "$now")" "$2" \
        > "$STATUS_FILE"
    chmod 600 "$STATUS_FILE"
}

# ---------- 磁盘水位门禁（>80% 拒跑：备份写满盘会连累业务盘） ----------
USAGE=$(df -P "$BACKUP_DIR" | awk 'NR==2 {gsub(/%/,""); print $5}')
if [ "$USAGE" -gt "$DISK_WARN_PERCENT" ]; then
    echo "错误：备份目标磁盘使用率 ${USAGE}% > ${DISK_WARN_PERCENT}%，拒跑（先清理旧备份或扩容）" >&2
    write_status fail "disk usage ${USAGE}% exceeds ${DISK_WARN_PERCENT}%"
    exit 1
fi

# ---------- 口令预检（口令不对要一眼可辨，不能等到 dump 中途才 Access denied） ----------
# 填错 MYSQL_ROOT_PASSWORD_BACKUP（例如另生成一个随机值、与服务器 root 口令不一致）时，
# 失败只出现在 mysqldump 的一行 stderr 里，而 cron 日志没人天天看——备份会一直悄悄失败，
# 直到磁盘或新鲜度告警才发现（D-090 实测）。这里提前一次 SELECT 1 并落失败状态。
if ! kcgl_mysql "$ROOT_PW" -uroot -N -B -e 'SELECT 1' >/dev/null 2>&1; then
    echo "错误：备份管理口令被 MySQL 拒绝——MYSQL_ROOT_PASSWORD_BACKUP 须与 root 真实口令一致，留空则沿用 MYSQL_ROOT_PASSWORD" >&2
    write_status fail "backup admin password rejected by mysql (check MYSQL_ROOT_PASSWORD_BACKUP)"
    exit 1
fi

# ---------- DB dump（zstd 压缩，SQL 文本 5-8 倍压缩比） ----------
DUMP_DIR="$BACKUP_DIR/daily/$TODAY"
mkdir -p "$DUMP_DIR"
echo "[$(TZ=Asia/Tokyo date '+%F %T')] 开始备份 → $DUMP_DIR"

if ! kcgl_mysqldump "$ROOT_PW" -uroot \
        --single-transaction --set-gtid-purged=OFF kcgl \
        | zstd -q -f -o "$DUMP_DIR/db.sql.zst"; then
    echo "错误：mysqldump/zstd 失败" >&2
    write_status fail "mysqldump or zstd failed"
    exit 1
fi
chmod 600 "$DUMP_DIR/db.sql.zst"

# ---------- 图片硬链快照（link-dest 指向最近一份，未变文件零空间） ----------
# rsync 在一次性容器内执行（宿主不直接触碰 docker 卷内部布局）；
# link-dest 用容器内路径：$BACKUP_DIR 挂载为 /backup
#
# 用随包交付的 kcgl-tools 镜像，**不在运行时装包**：曾经写的是 alpine + `apk add rsync`，
# 即每晚备份都要连公网包源；实测 alpine 源一次 SSL 抖动就让备份失败并中止了整条升级流程。
# 离线交付的产品里，关键任务的依赖必须随包到位（deploy/tools/Dockerfile 有完整理由）。
#
# rsync 的路径参数必须留在 `sh -c "…"` 里，不能直接当 docker run 的参数：Git Bash（MSYS）
# 会把 `/data/images/` 改写成带盘符的路径（D:/...），rsync 见冒号即判为远端主机，报
# 「The source and destination cannot both be remote」（改这行时实测踩到，第 7 次同族问题）。
TOOLS_IMAGE="ghcr.io/leon-ly/kcgl-tools:${KCGL_VERSION:-latest}"
if ! docker image inspect "$TOOLS_IMAGE" >/dev/null 2>&1; then
    echo "错误：缺少备份工具镜像 $TOOLS_IMAGE（先装载交付包内的 images/kcgl-tools-*.tar）" >&2
    write_status fail "tools image $TOOLS_IMAGE not loaded"
    exit 1
fi

# 图片目录由 app 按需懒创建——全新部署（尚无任何照片）时 /data/images 不存在，
# 「没有图片」不是失败：否则空部署下每晚 cron 必失败、kcgl-doctor 备份新鲜度
# 随之误报（本地预览抓出）。探测语句整段走 sh -c：裸 /data/images 参数在
# Git Bash（MSYS）下会被改写成 D:/soft/Git/data/images。
IMAGES_NOTE="images: none yet"
if docker run --rm -v "$IMAGE_VOLUME":/data:ro "$TOOLS_IMAGE" sh -c '[ -d /data/images ]'; then
    PREV_SNAP=$(ls -1d "$BACKUP_DIR"/daily/*/images 2>/dev/null | sort | tail -1 || true)
    PREV_DAY=${PREV_SNAP:+$(basename "$(dirname "$PREV_SNAP")")}
    LINK_ARG=""
    [ -n "$PREV_DAY" ] && [ "$PREV_DAY" != "$TODAY" ] && LINK_ARG="--link-dest=/backup/daily/$PREV_DAY/images"

    if ! docker run --rm \
            -v "$IMAGE_VOLUME":/data:ro \
            -v "$BACKUP_DIR":/backup \
            "$TOOLS_IMAGE" sh -c "rsync -a --delete $LINK_ARG /data/images/ /backup/daily/$TODAY/images/"; then
        echo "错误：图片 rsync 快照失败" >&2
        write_status fail "image rsync snapshot failed"
        exit 1
    fi
    IMAGES_NOTE="images snapshot at $TODAY"
else
    echo "提示：/data/images 尚不存在（从未上传照片），本次跳过图片快照"
fi

# ---------- weekly 留档（周一：硬链到当日，零成本） ----------
if [ "$WEEKDAY" = 1 ]; then
    cp -al "$DUMP_DIR" "$BACKUP_DIR/weekly/$TODAY" 2>/dev/null || \
        rsync -a --link-dest="$DUMP_DIR" "$DUMP_DIR/" "$BACKUP_DIR/weekly/$TODAY/"
fi

# ---------- 保留策略：daily 保 7、weekly 保 4（目录名日期排序） ----------
# 无匹配时 ls 退出码 2，叠加 set -e -o pipefail 会在备份成功之后、写状态文件之前
# 静默中止（全新部署 weekly 必为空 → 状态永远 fail）。helper 内 `|| true` 兜住。
list_dirs() { ls -1d "$@"/*/ 2>/dev/null || true; }
list_dirs "$BACKUP_DIR/daily" | sort | head -n -7 | xargs -r rm -rf
list_dirs "$BACKUP_DIR/weekly" | sort | head -n -4 | xargs -r rm -rf

DUMP_BYTES=$(stat -c %s "$DUMP_DIR/db.sql.zst")
write_status ok "db $(numfmt --to=iec "$DUMP_BYTES" 2>/dev/null || echo "${DUMP_BYTES}B"), $IMAGES_NOTE"
echo "[$(TZ=Asia/Tokyo date '+%F %T')] 备份完成：db.sql.zst（${DUMP_BYTES} 字节）；$IMAGES_NOTE"
