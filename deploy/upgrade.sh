#!/bin/bash
# kcgl 版本升级 / 回滚（docs/deployment-ja.md §5.2・§5.4 的可执行版本，同一支命令两个方向通用）。
#
# 用法：./upgrade.sh <新版包目录> [版本]
#   升级：./upgrade.sh ../kcgl-v1.1.0
#   回滚：./upgrade.sh ../kcgl-v1.0.0        （把旧版包目录当「新版」交给它即可）
#
# 为什么要有这支脚本（而不是让甲方照着手册敲五行）：M7 版本升级演练实测出两件事——
#
# ① 手册原文只让同步 migrations/，**没让同步 docker-compose.yml**。而「发布版本透传进 app
#    容器」这一行恰在 compose 里：镜像换成了新版、登录正常、doctor 全绿，可「システム状況」
#    的アプリバージョン仍显示旧值，§5.3 的核对项永远通不过（演练实测：镜像已是 v1.0.1，
#    appVersion 仍是 0.1.0-SNAPSHOT）。部署工件是**一整套**，必须整批同步。
#
# ② 手册原文称「不加 --force-recreate 则新迁移不会执行」。实测不成立：Compose v5.5.1 下
#    即使零配置变更，`up -d` 也会把已退出的一次性容器重新拉起，新迁移照常应用（用真实
#    新增迁移验证：schema 4 → 5）。所以本脚本**不靠"加了 --force-recreate 就算升级成功"
#    这种因果解释**，而是把结果显式断言出来：migrate 容器退出码、Schema 版本、app 容器
#    里实际生效的 KCGL_VERSION——任何一项对不上就红着脸退出。
#
# 为什么先备份：升级/回滚是**唯一会改库结构**的常规操作。§5.1 的备份是回滚的兜底；
# 事后才想起备份就来不及了，故此处强制（失败即中止，不提供跳过开关）。
set -euo pipefail
cd "$(dirname "$0")"

NEWPKG="${1:-}"
VERSION="${2:-}"
if [ -z "$NEWPKG" ]; then
    echo "用法：./upgrade.sh <新版包目录> [版本]（版本省略时取新版包 .env.example 的 KCGL_VERSION）" >&2
    exit 2
fi
[ -d "$NEWPKG" ] || { echo "错误：新版包目录不存在：$NEWPKG" >&2; exit 2; }
if [ ! -f .env ] || [ ! -f docker-compose.yml ]; then
    echo "错误：请在**当前部署目录**（含 .env 与 docker-compose.yml）内执行本脚本" >&2
    exit 2
fi

# ---------- 版本号：以新版包 .env.example 为准，并与其镜像 tar 名交叉核对 ----------
# 三处（.env.example / tar 名 / 下面要写进 .env 的值）不一致是典型的手工升级事故源：
# 镜像装了 v1.1.0 而 .env 写 v1.0.9，compose 会去找不存在的镜像而在 up 中途失败。
if [ -z "$VERSION" ]; then
    VERSION=$(sed -n 's/^KCGL_VERSION=//p' "$NEWPKG/.env.example" 2>/dev/null | head -1)
fi
[ -n "$VERSION" ] || { echo "错误：无法确定版本号（新版包缺 .env.example 的 KCGL_VERSION，请显式传第二个参数）" >&2; exit 2; }

for kind in app web; do
    tar_path="$NEWPKG/images/kcgl-$kind-$VERSION.tar"
    if [ ! -f "$tar_path" ]; then
        echo "错误：新版包缺少镜像 $tar_path（包内实有：$(ls -1 "$NEWPKG/images" 2>/dev/null | tr '\n' ' ')）" >&2
        exit 2
    fi
done

OLD_VERSION=$(sed -n 's/^KCGL_VERSION=//p' .env | head -1)
echo "== kcgl $OLD_VERSION → $VERSION =="
echo "   新版包：$NEWPKG"

# ---------- 1. 升级前备份（强制：回滚的兜底，失败即中止） ----------
echo "  [1/5] 升级前备份 …"
./backup.sh

# ---------- 2. 装载新镜像（从包内 tar，不依赖外网；网上一个 tag 也拉不到） ----------
echo "  [2/5] 装载镜像 tar …"
docker load -i "$NEWPKG/images/kcgl-app-$VERSION.tar"
docker load -i "$NEWPKG/images/kcgl-web-$VERSION.tar"
# 运维工具镜像（rsync）随包交付：备份的图片快照靠它，缺了 nightly 备份会失败。
# 旧版包内没有这个 tar（它是随 D-094 一起加的），故存在才装载，不视为错误。
if [ -f "$NEWPKG/images/kcgl-tools-$VERSION.tar" ]; then
    docker load -i "$NEWPKG/images/kcgl-tools-$VERSION.tar"
else
    echo "        该版本包内无 kcgl-tools 镜像 tar（旧版包），跳过装载"
fi
# 第三方基础镜像（mysql:8.4 / flyway:11-alpine）：compose 的 mysql/grants/migrate 靠它们，
# 宿主上没有、包内又没有时，up 会去联网拉取（国内 mirror 实测可停滞十余分钟，D-096）。
# 这里只做**装载**；"是否齐备"的判定归 [5/5] 的断言（本脚本：动作在前、校验在后）。
if [ -f "$NEWPKG/images/mysql-8.4.tar" ]; then
    docker load -i "$NEWPKG/images/mysql-8.4.tar"
fi
if [ -f "$NEWPKG/images/flyway-11-alpine.tar" ]; then
    docker load -i "$NEWPKG/images/flyway-11-alpine.tar"
fi

# ---------- 3. 整批同步部署工件 ----------
# 复制一律走「写临时名 + mv 原子替换」：本脚本自己也可能在新包内（会被覆盖），
# 就地截断重写会让正在执行的 bash 按偏移量读到半截脚本（cp 保持同一 inode）。
# mv 是同目录 rename，换新 inode，运行中的 bash 继续读旧 inode，安全。
echo "  [3/5] 同步部署工件（compose / 脚本 / 迁移）…"
put() {  # $1=源  $2=目标（均含路径）
    cp -p "$1" "$2.new" && mv -f "$2.new" "$2"
}
put "$NEWPKG/docker-compose.yml" docker-compose.yml
for f in backup.sh restore.sh verify-grants.sh kcgl-doctor.sh security-selfcheck.sh db-cli.sh upgrade.sh; do
    [ -f "$NEWPKG/$f" ] && put "$NEWPKG/$f" "$f"
done
chmod +x ./*.sh
# 迁移只增不改：新包多出的 SQL 覆盖进去，旧文件保留（Flyway 按版本号决定是否执行）
cp -p "$NEWPKG"/migrations/*.sql migrations/

# .env 只改版本号一行：口令/端口/路径是甲方资产，脚本绝不触碰。
if grep -q '^KCGL_VERSION=' .env; then
    sed -i "s|^KCGL_VERSION=.*|KCGL_VERSION=$VERSION|" .env
else
    printf '\nKCGL_VERSION=%s\n' "$VERSION" >> .env
fi
echo "        .env: KCGL_VERSION=$OLD_VERSION → $VERSION"

# ---------- 4. 重建栈 ----------
# --force-recreate：不依赖 compose 的变更检测（它只看配置差异，不看「我以为我是新版」）。
# --remove-orphans：新包若删过服务，清掉遗留容器。
echo "  [4/5] docker compose up -d --force-recreate --remove-orphans …"
docker compose up -d --force-recreate --remove-orphans

# ---------- 5. 结果断言（升级是否真的生效，只认证据） ----------
echo "  [5/5] 校验 …"
APP_CID=$(docker compose ps -q app | head -1)
[ -n "$APP_CID" ] || { echo "错误：app 容器未创建，升级失败" >&2; exit 1; }

for i in $(seq 1 120); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$APP_CID" 2>/dev/null)" = healthy ] && break
    sleep 3
done
FAILED=0

# 基础镜像齐备性：compose 的 mysql/grants 用 mysql:8.4、migrate 用 flyway/flyway:11-alpine。
# 缺任何一个，`up -d` 都会转去联网拉取（国内 mirror 实测可停滞十余分钟，D-096）。
# 放在断言段而非装载段：装载是动作、齐备性才是不变量。
assert_base() {  # $1=包内 tar 名  $2=镜像 tag
    if docker image inspect "$2" >/dev/null 2>&1; then
        echo "  ✓ 基础镜像就位：$2"
    else
        echo "  ✗ 缺少基础镜像 $2（应 docker load -i images/$1）——up 时会尝试联网拉取"
        FAILED=1
    fi
}
assert_base mysql-8.4.tar mysql:8.4
assert_base flyway-11-alpine.tar flyway/flyway:11-alpine

# 一次性容器必须成功退出：migrate 失败时 app 起不来，但 grants 失败也可能被忽略
for one in migrate grants; do
    cid=$(docker compose ps -aq "$one" 2>/dev/null | head -1)
    code=$(docker inspect -f '{{.State.ExitCode}}' "$cid" 2>/dev/null || echo "?")
    if [ "$code" != "0" ]; then
        echo "  ✗ $one 容器退出码 $code（应为 0）——日志："
        docker compose logs "$one" 2>&1 | tail -15
        FAILED=1
    else
        echo "  ✓ $one 成功退出"
    fi
done

# 运行镜像必须是目标版本（升/回两个方向都成立的最强不变量）
image=$(docker inspect -f '{{.Config.Image}}' "$APP_CID" 2>/dev/null || true)
case "$image" in
    *":$VERSION") echo "  ✓ 运行镜像：$image" ;;
    *) echo "  ✗ 运行镜像 '$image' 与目标版本 ':$VERSION' 不符（镜像未装载或 .env 未生效？）"; FAILED=1 ;;
esac

# 版本透传只在**该版本的 compose 声明了它**时才可断言。
# 为什么不能无条件断言：旧包的 compose 里根本没有这一行（它是随 KCGL_VERSION 展示修复
# 一起加的），回滚到旧包时容器内为空是**正确结果**——无条件断言会把成功的回滚报成失败
# （演练实测踩到）。故按「目标包自己声明的能力」决定断言还是仅提示。
if grep -qE '^[[:space:]]+KCGL_VERSION:' "$NEWPKG/docker-compose.yml"; then
    live_env=$(docker exec "$APP_CID" printenv KCGL_VERSION 2>/dev/null || true)
    if [ "$live_env" = "$VERSION" ]; then
        echo "  ✓ app 容器内 KCGL_VERSION=$live_env"
    else
        echo "  ✗ app 容器内 KCGL_VERSION='$live_env'，期望 '$VERSION'（compose 未同步？）"
        FAILED=1
    fi
else
    echo "  · 该版本的 compose 未透传 KCGL_VERSION（旧包）：应用内显示的版本可能仍是清单版"
fi

# Schema 版本（只报告，不断言）：本次发布若无迁移，版本号不变是正确的
schema=$(docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B -e "SELECT MAX(version) FROM kcgl.flyway_schema_history"' 2>/dev/null || true)
echo "  · Schema 版本：${schema:-（读取失败，请手工核对 docker compose logs migrate）}"
docker compose logs migrate 2>&1 | tail -3 | sed 's/^/      /'

if [ "$FAILED" != "0" ]; then
    echo "== 升级未通过自检 ==" >&2
    echo "   回滚：./upgrade.sh <旧版包目录>" >&2
    echo "   取备份：备份目录在 KCGL_BACKUP_DIR，恢复见 docs/運用手順書.md" >&2
    exit 1
fi

./kcgl-doctor.sh

echo "== 完成：$OLD_VERSION → $VERSION =="
cat <<'EOT'
   最後にブラウザでログインし、「システム状況」ページの「アプリバージョン」が
   上記のバージョンであることを確認してください（§5.3 チェックリスト）。
   ・旧版のイメージは起動時に KCGL_VERSION を読まないため、切り戻し時にここが旧い
     マニフェスト版（0.1.0-SNAPSHOT）と表示されることがあります。異常ではありません。
EOT
