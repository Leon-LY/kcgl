#!/bin/bash
# kcgl DB 客户端封装（被 backup.sh / restore.sh / verify-grants.sh / security-selfcheck.sh source）。
# 只此一处定义「怎么把口令交给 mysql 客户端」，避免四份脚本各写一套而漂移。
#
# 为什么口令不走 docker 命令行参数：`-p<pw>`、`-e MYSQL_PWD=<pw>` 都是**命令行参数**，
# 而 Git Bash（MSYS）会把含 `/` 的参数当路径改写——`openssl rand -base64 24` 生成的口令
# 含 `/` 是常态（docs/deployment-ja.md 就用它），实测 32 字符被改成 43/45 字符 → 认证失败。
# 后果分两档：备份/巡检会**响亮报错**；安全自检里却以「越权被拒 ✓」的样子**假绿**
# （四条负例全部无意义），这是 D-088 干净主机演练抓出的真问题。
# Ubuntu（甲方生产）无此改写，但同一份脚本要在两端都可信，故统一改走 stdin。
# 附带收益：口令不再出现在宿主 `ps` 的 docker 参数里。
#
# 约定：口令占容器 stdin 的**首行**（`read` 只吃一行）；其余部分若有，原样留给
# mysql/mysqldump 当输入流（恢复导入就是靠这一点）。

# kcgl_mysql <口令> <mysql 参数…>
kcgl_mysql() {
    local pw="$1"
    shift
    printf '%s\n' "$pw" | docker compose exec -T mysql \
        sh -c 'IFS= read -r p; MYSQL_PWD="$p" exec mysql "$@"' _ "$@"
}

# kcgl_mysqldump <口令> <mysqldump 参数…>   —— stdout 即 dump 流
kcgl_mysqldump() {
    local pw="$1"
    shift
    printf '%s\n' "$pw" | docker compose exec -T mysql \
        sh -c 'IFS= read -r p; MYSQL_PWD="$p" exec mysqldump "$@"' _ "$@"
}

# kcgl_mysql_import <口令> <目标库>   —— 本函数自身的 stdin 即 SQL 文本流，
# 由函数前置口令行后整体送给容器（zstd -dc … | kcgl_mysql_import "$PW" kcgl）。
kcgl_mysql_import() {
    local pw="$1" db="$2"
    { printf '%s\n' "$pw"; cat; } | docker compose exec -T mysql \
        sh -c 'IFS= read -r p; MYSQL_PWD="$p" exec mysql -uroot "$1"' _ "$db"
}
