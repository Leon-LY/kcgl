# 压测造数（M7-④）

按 docs/01 §4.2 分布规格生成 5 万件压测数据，验证系统在数据量上限
（5 万件上限是业务约束）下的性能门槛：列表 p95 < 500ms、搜索 p95 < 1s、
保存 p95 < 300ms、大盘 p95 < 1s、10 并发录入 ≥ 5 件/s、10 用户 SSE。

## 工件

| 文件 | 作用 |
|------|------|
| `seed-bench.sql` | 造数主体：TRUNCATE 业务表 → 5 万 item / ~12.5 万 image / ~14 万 ledger（root 通道执行） |
| `bench-images.sh` | 占位 JPEG 硬链接落盘（25 万文件零拷贝；母本在 `fixtures/`，首次运行自动生成） |
| `bench-verify.sql` | 六断言 + 分布报告——造数后必跑，全部 OK 才可用于压测 |
| `k6/bench.js` | 四场景压测（browse/search/dashboard/entry），按 tags 分桶断言 p95 与录入吞吐 |
| `k6/sse-check.sh` | SSE 十连接并发保持 + 广播到达断言（第六门槛） |

## 执行顺序（deploy/ 目录）

```bash
# 1. 造数（⚠ 清空业务表；压测专用库口径）
docker compose exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
  --default-character-set=utf8mb4 kcgl < bench/seed-bench.sql

# 2. 图片落盘（读 .env；幂等可重跑）
./bench/bench-images.sh

# 3. 自检（六断言全 OK + 分布偏差 <1 个百分点）
docker compose exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
  --default-character-set=utf8mb4 kcgl < bench/bench-verify.sql
```

> 客户端字符集：mysql:8.4 容器内的 `mysql` 客户端在无 UTF-8 locale 时默认
> latin1，日文语料会被双重编码（静默乱码）。SQL 文件自带 `SET NAMES utf8mb4`
> 已免疫，命令行再显式带上 `--default-character-set=utf8mb4` 是双保险 +
> 让意图可见（D-080）。

### 字符集事故的修复（曾以错误字符集灌过数据）

`item` 等业务表会被第 1 步 TRUNCATE 重灌，自动恢复；但**字典行是「存在即跳过」
的幂等插入**（`auction_venue` 三行 + `bench-op1..3` 的 display_name），乱码不会
自愈——按下述修好后重跑第 1~3 步：

```bash
docker compose exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
  --default-character-set=utf8mb4 kcgl <<'SQL'
UPDATE auction_venue SET name = '名古屋会場'           WHERE code = 'HT';
UPDATE auction_venue SET name = '東京モーターズ会場'   WHERE code = 'MK';
UPDATE auction_venue SET name = '関西エクスチェンジ会場' WHERE code = 'EX';
UPDATE sys_user SET display_name = 'ベンチ演習1' WHERE username = 'bench-op1';
UPDATE sys_user SET display_name = 'ベンチ演習2' WHERE username = 'bench-op2';
UPDATE sys_user SET display_name = 'ベンチ演習3' WHERE username = 'bench-op3';
SQL
```

（ledger 的 operator_name 是插入时的快照，重跑第 1 步即随之刷新。）

## 设计要点（D-078）

- **确定性随机**：全部随机值由 `CRC32('盐' + 序号)` 派生——无原生
  seed RNG 依赖，重跑结果逐位一致，压测基线可比。
- **生成即合法**：ledger 动作矩阵全部走 InventoryStateMachine 合法边
  （M3 边表），终态与 item 快照一致；数据必须通过 restore.sh /
  LedgerConsistencyService 同口径的三条生产级对账断言——失败即
  seeder bug，不是「压测数据差不多就行」。
- **管理号**：按 D-068 格式 `{会场}{月}-{前缀}{流水}{价格码}` 分桶
  分配，seq_item_code 计数器回填后，真实录入从桶位之后继续——
  造数与业务可共存（压测录入不会撞号）。
- **图片**：元数据入库 + 文件硬链接（两枚母本 JPEG）——文件数/inode/
  目录层级压力真实，磁盘占用近零（共享测试机磁盘约束，勘察 D-077）。
- **ledger ~14 万行**：动作矩阵自然落点（规格 15 万的 93%）——
  在途 10% 单 CREATE、在库 55% 含调拨/上架、已出库 35% 按卖出 60%/
  报废 20%/退货重卖 12%/退回会场 8% 分型；不为凑整数行注水。
- **bench 操作员**：`bench-op1..4`（EDITOR 角色、可登录）既是 ledger/item
  的 `created_by`/`operator_id` 数值源，**也是压测与 SSE 验证的登录账号池**——
  SecurityConfig `maximumSessions(8)` 决定了单账号最多 8 个并发会话，22 VU 的
  压测必须分摊到多个账号（D-083）。账号用 upsert 写入，旧行（placeholder hash /
  enabled=0）会被自愈。压测**不用** admin：最小授权，且本就不该需要管理员权限。
- **分布自检口径**：六断言中 A1-A3 与 restore.sh run_assertions() /
  LedgerConsistencyService 三方同口径——造数、备份演练、生产自检
  共用一套不变量定义，改一处必须三方同步（见 restore.sh 头注）。

## 本地预演实测（2026-09-30，D-080）

三件套在本地部署栈（Docker Desktop / Windows 11）完整跑通一轮，数据如下；
**本地数字只用于证明脚本可执行，不作为性能门槛依据**（宿主虚拟化开销大，
门槛须在服务器侧复测）。

| 项 | 实测 |
|----|------|
| 造数耗时 | seed-bench.sql 单遍成功（SEED_EXIT=0） |
| 规模 | 50,000 item / 125,629 item_image / 127,922 ledger |
| 管理号前缀 | 459 个单字母桶 + 63 个双字母桶（HT 4..11 月进入 AA..AR） |
| 分布偏差 | 会场 60.1/24.9/15.1、状态 9.8/55.0/35.1、仓库 67.1/32.9、图片均值 2.513、备注 60.3%（均长 122.0 字）、日期跨度 18 个月——皆 <1 个百分点 |
| 六断言 | A1..A6 全 OK，violations=0，退出码 0 |
| 图片落盘 | 251,258 个硬链接（= 行数 × 2），母本池 7 份，约 1 分 38 秒 |
| 磁盘占用 | 近零（硬链接共享 7 枚母本） |
| k6 四场景 | K6_EXIT=0：checks 100%（6,664/6,664）、http_req_failed 0.00%、`kcgl_session_kicked` 0 |
| 时延/吞吐 | browse p95 23.4ms（<500）、search p95 51.2ms（<1s）、dashboard p95 81.1ms（<1s）、entry 保存 p95 28.9ms（<300）、10 并发录入 7.56 件/s（≥5） |
| SSE 门槛 | 10/10 连接保持且各收到本次触发的管理号广播（SSE_EXIT=0） |

> **本地数字只证明链路可跑通，不作性能结论**——宿主机是 Docker Desktop/Windows，
> 虚拟化开销与共享测试机差异都很大，六项门槛须在**服务器侧复测**后写入验收报告。

> 双字母桶的出现是「base-26 前缀修复」生效的直接证据：单桶规模超 26×99=2574
> 时前缀自然进位到 AA，与 app 侧 `ItemCodeFormatter.nextPrefix` 同进位序。
>
> 在途件（`stockStatus=0`，7,174 行）的 `item_name`/`category` 为 NULL 是**设计如此**：
> 在途 = 刚录入、只登记买入信息（会场/日期/价格/仓库），录入表单不采集商品名。
> 因此关键词检索叠 `stockStatus=0` 必然 0 命中——k6 的搜索场景据此剔除该组合
> （在途筛选仍由 browse 覆盖），详见 `k6/bench.js` 的 `SEARCH_FILTERS` 注释。

## 压测执行（k6，部署后跑）

数据就绪（1→2→3 全 OK）后执行——需宿主装 k6 ≥ 0.49，以及 seed-bench.sql 建的
**bench 账号池**（bench-op1..4，密码见 seed-bench.sql 注释；不是 admin）：

```bash
# 四场景：browse/search/dashboard/entry（按 tags 分桶断言 p95 + 录入吞吐 ≥5 件/s）
# K6_USERS 是**账号池**，不是可选项——SecurityConfig maximumSessions(8) 让单账号
# 最多 8 个并发会话，第 9 个登录踢掉最早的，被踢会话此后每次请求 401。本脚本峰值
# 22 VU（browse 5 + search 5 + dashboard 2 + entry 10）→ 至少要 3 个账号、默认 4 个。
K6_PASS='Kcgl-bench-2026' \
k6 run -e BASE=http://<host>:8082 -e VENUE_ID=1 \
       -e K6_USERS=bench-op1,bench-op2,bench-op3,bench-op4 \
       -e K6_PASS="$K6_PASS" bench/k6/bench.js

# SSE 十连接并发保持 + 广播到达（第六门槛）；10 会话按同一账号池轮转分摊（每账号 ≤3）
K6_USERS=bench-op1,bench-op2,bench-op3,bench-op4 \
./bench/k6/sse-check.sh http://<host>:8082 "$K6_PASS"
```

> ⚠ 用 admin 单账号跑这套脚本是**已知会红**的：22 VU 撞 8 会话上限 → 踢人 →
> 全网流量退化成 401（app 侧几乎收不到鉴权请求）。脚本已把这条做成显式门槛
> `kcgl_session_kicked: count==0`——非 0 即账号池不够，不会静默变成几行 checks 失败。
>
> ⚠ **脚本内所有会话 Cookie 都手工回填到请求头**（D-082）：k6 的 cookie jar 只在
> 设置它的那一轮迭代发出 Cookie，下一轮起不再发送 —— 表现为「登录 200，之后全部
> 401」，压测测到的其实是 401 的响应时间。用 k6 重写压测脚本时勿改回 jar 自动管理。

entry 场景写入真实在途件（压测专用库口径）——压测后按需重跑 seed-bench.sql
复位分布。六项门槛与分布规格见 docs/01 §4.2；结果记入 docs/qa 压测报告。
