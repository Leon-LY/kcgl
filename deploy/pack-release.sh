#!/bin/bash
# kcgl 生产交付包打包（M7-⑤，docs/01 十二节交付物）。
#
# 为什么是「镜像 tar + 工件目录」而不是「客户端拉 ghcr」：甲方在日本自部署，
# 不依赖我方 GitHub 账号/仓库可见性/跨境网络——包内自带镜像 tar，docker load 即得，
# 全程可离线。测试环境（CI 有凭据）仍走 ghcr pull（deploy.yml），两条路互不影响：
# 镜像 tag 相同，compose 见到本地已有同名镜像就不再拉取。
#
# 产物结构（$OUT/kcgl-<版本>/）：
#   docker-compose.yml .env.example db-init/ nginx/ grants.sql migrations/
#   backup.sh restore.sh verify-grants.sh kcgl-doctor.sh security-selfcheck.sh
#   images/kcgl-app-<版本>.tar  images/kcgl-web-<版本>.tar
#   docs/デプロイ手順書.md docs/運用手順書.md（甲方日文文档）
#   MANIFEST.txt（`#` 头部 + sha256 行，甲方可直接 sha256sum -c 校验文件完整性）
#
# 用法：./pack-release.sh <版本号> [输出目录]      例：./pack-release.sh v1.0.0 ../dist
# 前置：本机可 docker build（app 由 server/Dockerfile 源码构建、web 由 web/Dockerfile）。
set -euo pipefail
cd "$(dirname "$0")"
CUR="$(pwd)"
ROOT="$(cd .. && pwd)"

VERSION="${1:-}"
[ -n "$VERSION" ] || { echo "用法：./pack-release.sh <版本号> [输出目录]（版本号须与 .env 的 KCGL_VERSION 一致）" >&2; exit 2; }
OUT="${2:-$ROOT/dist}"
PKG="$OUT/kcgl-$VERSION"

APP_IMAGE="ghcr.io/leon-ly/kcgl-app:$VERSION"
WEB_IMAGE="ghcr.io/leon-ly/kcgl-web:$VERSION"

echo "== 打包 kcgl $VERSION → $PKG =="
rm -rf "$PKG"
mkdir -p "$PKG/images" "$PKG/docs" "$PKG/migrations"

# ---------- 1. 部署工件（显式列举：多拷一个开发脚本进交付包=多一份被误用的风险） ----------
for f in docker-compose.yml .env.example grants.sql backup.sh restore.sh verify-grants.sh kcgl-doctor.sh security-selfcheck.sh db-cli.sh; do
    cp -p "$f" "$PKG/"
done
cp -rp db-init nginx "$PKG/"

# ---------- 2. 迁移目录（单一事实源=仓库 server/src/main/resources/db/migration；
#               镜像内也有一份，此处外挂是为了让「迁移与镜像同版本」在包内可核） ----------
cp -p "$ROOT"/server/src/main/resources/db/migration/*.sql "$PKG/migrations/"

# ---------- 3. 双镜像（源码构建，tag 与部署 compose 期望一致） ----------
echo "  构建 app 镜像 …"
docker build -q -t "$APP_IMAGE" "$ROOT/server" >/dev/null
echo "  构建 web 镜像 …"
docker build -q -t "$WEB_IMAGE" "$ROOT/web" >/dev/null
docker save "$APP_IMAGE" -o "$PKG/images/kcgl-app-$VERSION.tar"
docker save "$WEB_IMAGE" -o "$PKG/images/kcgl-web-$VERSION.tar"

# ---------- 4. 甲方日文文档（与镜像同版本交付；中文工程文档不入包） ----------
# 仓库内文件名是工程向（deployment-ja/runbook），包内换成甲方看得懂的名字。
copy_ja_doc() {  # $1=仓库内相对 docs/ 的路径 $2=包内文件名
    if [ -f "$ROOT/docs/$1" ]; then
        cp -p "$ROOT/docs/$1" "$PKG/docs/$2"
    else
        echo "错误：缺少甲方文档 docs/$1（交付包必须自带日文部署/运维文档）" >&2
        exit 1
    fi
}
copy_ja_doc "deployment-ja.md" "デプロイ手順書.md"
copy_ja_doc "runbook.md"       "運用手順書.md"

# ---------- 5. 清单与校验和 ----------
# 头部行一律以 `#` 开头：sha256sum -c 会**静默忽略** # 注释行（实测 GNU coreutils），
# 于是甲方在本目录直接 `sha256sum -c MANIFEST.txt` 就能一次过。曾经写成人类可读的
# 纯文本头（"kcgl 交付包 …"/"== sha256 =="），校验虽也退出 0，却会伴随一串
# "improperly formatted" 警告——非专业读者会以为包坏了（D-086 收尾发现）。
{
    echo "# kcgl 交付包 $VERSION"
    echo "# 打包时间（JST）：$(TZ=Asia/Tokyo date '+%F %T')"
    echo "# 镜像：$APP_IMAGE / $WEB_IMAGE"
    echo "# 校验方法：在本目录执行 sha256sum -c MANIFEST.txt（全部 OK 即文件完整）"
    (cd "$PKG" && find . -type f ! -name MANIFEST.txt -print0 | sort -z | xargs -0 sha256sum)
} > "$PKG/MANIFEST.txt"

echo "== 完成 =="
du -sh "$PKG" 2>/dev/null || true
echo "交付步骤（甲方侧）：scp/介质送达 → tar -xf → docker load -i images/*.tar → cp .env.example .env（填强口令）→ docker compose up -d"
echo "完整性核对（甲方侧，任选）：cd kcgl-$VERSION && sha256sum -c MANIFEST.txt"
echo "自检（我方或甲方）：cd kcgl-$VERSION && ./security-selfcheck.sh"
cd "$CUR"
