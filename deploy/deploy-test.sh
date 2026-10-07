#!/usr/bin/env bash
# 测试环境部署 —— 本机执行的那一半：镜像交付 + 滚动更新 + 结果核对。
#
# 【为什么这件事拆成两半，以及为什么这一半只能在本机跑】
# 测试服务器（腾讯云北京）的**国际出口被掐到 15–23 KB/s**，双向都是。2026-10-07
# 实测三个数字：
#   - 服务器自己 `docker compose pull` 从 ghcr 拉：跑了 56 分钟，一个镜像都没拉完；
#   - GitHub runner（境外）代下后经 SSH 直送服务器：15 KB/s，600MB 要 11 小时；
#   - 本机（境内）→服务器 `docker save | ssh docker load`：**18 MB/s，33 秒**。
# 差 1000 倍，差别只在有没有跨境。所以把活按**网络位置**切：
#   能跨境的（构建推送 ghcr、按精确 commit 同步制品）留给 CI；
#   需要跨境取镜像的这一段放到本机。
#
# 【前置条件】CI 的同步作业必须先跑完
# `.github/workflows/deploy.yml` 的「部署测试环境」作业负责：同步 migrations/、
# 同步 deploy/ 工件、把 /opt/kcgl/.env 的 KCGL_VERSION 回写成目标版本。
# 本脚本以「.env 已回写为该版本」作为 CI 就绪的凭证——未就绪直接中止，不带着
# 半套制品去动容器（migrations 与镜像必须同版本，见 deploy.yml 顶部注释）。
#
# 【用法】
#   git tag v-test-<短SHA> && git push origin v-test-<短SHA>   # 触发 CI 构建+同步
#   deploy/deploy-test.sh v-test-<短SHA>                       # 等 CI 绿了再跑本脚本
#
# 环境变量（均有默认值，一般不用改）：
#   TEST_SSH_HOST / TEST_SSH_USER / DEPLOY_DIR / IMAGE_APP / IMAGE_WEB
set -euo pipefail

VERSION="${1:-}"

usage() {
  echo "用法：$0 <版本tag>    例：$0 v-test-8667593" >&2
  echo "      版本 tag 须与 CI 构建出的镜像 tag 一致（推 v* tag 时 = tag 名）。" >&2
}

if [ -z "$VERSION" ]; then
  usage
  exit 2
fi
# 版本 tag 会拼进远端 shell 命令，先卡死字符集，避免注入
case "$VERSION" in
  *[!A-Za-z0-9._-]*) echo "✗ 版本 tag 含非法字符：$VERSION" >&2; exit 2 ;;
esac

SSH_TARGET="${TEST_SSH_USER:-root}@${TEST_SSH_HOST:-49.232.49.175}"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/kcgl}"
IMAGE_APP="${IMAGE_APP:-ghcr.io/leon-ly/kcgl-app}"
IMAGE_WEB="${IMAGE_WEB:-ghcr.io/leon-ly/kcgl-web}"

# BatchMode：不交互；ConnectTimeout：网络不通时快速失败而不是挂着
ssh_do() { ssh -o BatchMode=yes -o ConnectTimeout=10 "$SSH_TARGET" "$@"; }

echo "==> 0/5 前置检查：CI 是否已把 .env 回写为 $VERSION"
env_line="$(ssh_do "grep '^KCGL_VERSION=' $DEPLOY_DIR/.env 2>/dev/null || true")"
if [ "$env_line" != "KCGL_VERSION=$VERSION" ]; then
  echo "✗ 服务器 .env 当前为「${env_line:-<读取失败或未设置>}」，与目标 $VERSION 不符。" >&2
  echo "  CI 的同步作业还没跑到这个版本（或跑的是别的版本）。先触发并等它变绿：" >&2
  echo "    git tag $VERSION && git push origin $VERSION" >&2
  exit 1
fi
echo "    ✓ $env_line"

echo "==> 1/5 本机拉取镜像（走本机网络，这是整个流程里最快的一段）"
docker pull "$IMAGE_APP:$VERSION"
docker pull "$IMAGE_WEB:$VERSION"

echo "==> 2/5 直送服务器装载"
# 不用「先 docker login」：这两个包在 ghcr 上是公开的，匿名可拉。
# 注意 docker save/load **不保留 RepoDigests**，所以装载进来的层在 docker 眼里
# 仍算「本地没有」——这也是为什么下一步必须配 --pull never 把 pull 这条路堵死。
docker save "$IMAGE_APP:$VERSION" "$IMAGE_WEB:$VERSION" | ssh_do 'docker load'

echo "==> 3/5 滚动更新"
# --pull never 是有意的：镜像刚由上一步装载，若这里放开 pull，compose 会去
# registry 核对并按 15 KB/s 重下，整个部署挂死在几小时的空转上。宁可因
# 「本地没有该镜像」立刻失败（那说明上一步漏装了），也不要静默等待。
# mysql:8.4 / flyway:11-alpine 是固定版本、服务器上长期存在，不在装载之列。
ssh_do "cd $DEPLOY_DIR && KCGL_VERSION=$VERSION docker compose up -d --pull never --force-recreate --remove-orphans"

echo "==> 4/5 核对结果（不信任 up 已生效，D-092/D-093）"
running="$(ssh_do "cd $DEPLOY_DIR && docker compose ps -q app | xargs -r docker inspect -f '{{.Config.Image}}'")"
case "$running" in
  *":$VERSION") echo "    ✓ app 运行镜像 $running" ;;
  *)
    echo "    ✗ app 运行镜像为「${running:-<空>}」，与目标 $VERSION 不符" >&2
    exit 1
    ;;
esac

migrate_rc="$(ssh_do "docker inspect -f '{{.State.ExitCode}}' kcgl-migrate")"
if [ "$migrate_rc" != "0" ]; then
  echo "    ✗ migrate 退出码 $migrate_rc（数据库迁移没跑通）" >&2
  exit 1
fi
echo "    ✓ migrate 退出码 0"

http_code="$(ssh_do "curl -fsS -o /dev/null -w '%{http_code}' http://127.0.0.1:8082/")"
if [ "$http_code" != "200" ]; then
  echo "    ✗ 登录页返回 $http_code（web/app/mysql 链路未通）" >&2
  exit 1
fi
echo "    ✓ 登录页 HTTP 200"

echo "==> 5/5 部署后巡检"
# 先等 web 容器的 healthcheck 转 healthy 再巡检：刚 up 完它还挂在 starting，
# 立刻跑 doctor 会得到一条恒假的「FAIL 容器 kcgl-web：running/starting」——
# 每次部署都报一条假故障，等于把巡检结果训练成噪音。
web_health=""
for _ in $(seq 1 24); do
  web_health="$(ssh_do "docker inspect -f '{{.State.Health.Status}}' kcgl-web 2>/dev/null || echo unknown")"
  [ "$web_health" = "healthy" ] && break
  sleep 5
done
echo "    web 容器健康状态：$web_health"
# 用 `bash kcgl-doctor.sh` 而不是 `./kcgl-doctor.sh`：与目标文件的执行位无关，
# 到哪儿都跑得动。（D-124 起因：这些脚本在 git 里曾长期是 100644——本机
# core.filemode=false，git 不记录执行位——CI 在 Linux 检出后 rsync 过来仍是 644，
# `./` 调用必然 Permission denied；而原 CI 那步写的是 `./kcgl-doctor.sh || true`，
# `|| true` 恰好把这个报错吞了，「部署后巡检」实际一直在空跑。执行位已在同一轮修掉，
# 这里保持显式 bash 只是不再依赖它。）
# 巡检输出仍不当作部署成败的判据（与 CI 同口径）：硬门槛是上一步的 200 + 版本核对。
ssh_do "cd $DEPLOY_DIR && bash kcgl-doctor.sh" || true

echo
echo "✓ 部署完成：测试环境已运行 $VERSION"
