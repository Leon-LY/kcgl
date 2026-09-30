# kcgl 运维工具镜像（rsync）——备份的图片硬链快照用它执行，随交付包一起交付。
#
# 为什么要有这个镜像：备份原先在一次性 alpine 容器里 `apk add rsync` 临时装 rsync，
# 即**每晚备份都依赖公网包源**。而本项目的交付前提是「甲方内网可离线起栈」，且备份
# 失败会连带 kcgl-doctor 的备份新鲜度告警、管理后台显示失败态——一个凌晨 03:30 的关键
# 任务不该被 CDN 抖动打断。M7 版本升级演练实测到这一点：alpine 源连续返回
# `unable to select packages: rsync (no such package)`（SSL EOF），备份直接失败并
# 中止了整条升级流程，而二十分钟前同一台机器上它还是成功的。
#
# 为什么不直接把 rsync 装进 kcgl-app 镜像（更省一个交付物）：那样 rsync 会随 app 版本
# 走——回滚到旧版 app 镜像（其中没有 rsync）就**同时失去了备份能力**，把「回滚」这个
# 应急操作变成新的故障源。运维工具必须与业务镜像解耦、单独带版本。
FROM debian:stable-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends rsync \
    && rm -rf /var/lib/apt/lists/*
# 不设 ENTRYPOINT：调用方按需 `sh -c "rsync …"`，保持与其它运维容器（alpine 裸跑）同构。
CMD ["rsync", "--version"]
