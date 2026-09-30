-- =====================================================================
-- kcgl 压测造数（M7-④，docs/01 §4.2 分布规格 + D-078）
--
-- 规模：5 万 item / ~12.5 万 image（期望均值 2.5）/ ~14 万 ledger
--   （ledger 为动作矩阵自然落点 140,150 行 = 规格 15 万的 93%——
--    每件动作数按真实业务比例分配，不为凑整数行注水）
-- 分布规格：
--   会场   HT 60% / MK 25% / EX 15%
--   状态   在途 10% / 在库 55% / 已出库 35%
--   日期   buy_date 跨 18 个月（2025-04-01..2026-09-25 固定窗口）
--   备注   60% 填充，均长 ~120 字（日文）
--   图片   0..9 加权 39/11/9/9/8/7/5/4/3/5，期望均值 2.50
--   仓库   名古屋 70% / 福岡 30%
-- 随机性：CRC32('盐'+序号) 确定性派生——固定可复现（压测基线可比）
-- 管理号：{会场}{月}-{前缀}{流水}{价格码}（D-068），桶=(venue,month)
--   跨年连续；桶内 ROW_NUMBER 分配 seq 1..99、99 进位前缀按 base-26 递进
--   （A→Z→AA→AZ，与 ItemCodeFormatter.nextPrefix 同进位序）。
--   单桶规模：桶键只取 MONTH(1-12)，而日期窗口跨 18 个月——出现两次的
--   月份（4..9 月）单桶达 HT 3 万×2/18≈3333 件 > 26×99=2574，**必须**能进
--   两位前缀。原按「12 桶均分≈2500 件」估的前提是错的：桶数不是 12 而是
--   按月份归并（D-080 实测撞号 HT5-a9B）。
-- ledger：全部走 InventoryStateMachine 合法边（M3 边表），终态与
--   item.stock_status/sale_status/warehouse 一致——生成即合法，
--   事后 bench-verify.sql 三断言复核（失败=本脚本 bug，D-078）
--
-- ⚠ 本脚本 TRUNCATE 全部业务表后重建（压测专用口径；TRUNCATE 同时
--   重置 AUTO_INCREMENT，item.id=序号 n 是确定性前提——第 5 节动作
--   子型按同盐 CRC32('o', id) 复算，与第 2 节分桶时完全同值）。
-- 跑法（root 通道，deploy/ 目录）：
--   docker compose exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" kcgl \
--     < bench/seed-bench.sql
--   图片文件另跑 bench-images.sh（占位 JPEG 硬链接落盘）
-- =====================================================================

-- mysql:8.4 容器里的 mysql **客户端**在无 UTF-8 locale 时把连接字符集落到 latin1，
-- 于是本文件的 UTF-8 字节被服务端按 latin1 收下再转 utf8mb4 = 双重编码乱码
-- （实测：item_name '萩焼湯呑' 落库为 hex C3A8C290C2A9，管理号等 ASCII 全对——
-- 只有非 ASCII 语料变形，故障静默）。与本文件自带 SET NAMES 一行即免疫：无论
-- 从哪个客户端、用什么 locale 灌数据都正确（mysqldump 输出同样以 SET NAMES
-- utf8mb4 开头，备份/恢复链路因此天然安全）。
SET NAMES utf8mb4;
-- ⚠ 字典三行（auction_venue）与 bench 操作员 display_name 是「存在即跳过」的幂等
--   插入：若曾用错字符集灌过一次（日文变双重编码乱码），重跑本脚本**不会自愈**
--   这三行——详见 README「字符集事故的修复」。
SET SESSION cte_max_recursion_depth = 60000;

-- ---------------------------------------------------------------------
-- 0. 清场（TRUNCATE 重置 AUTO_INCREMENT；schema 归 Flyway 管，不动结构）
-- ---------------------------------------------------------------------
TRUNCATE TABLE item_image;
TRUNCATE TABLE stock_ledger;
TRUNCATE TABLE yahoo_listing;
TRUNCATE TABLE yahoo_import_batch;
TRUNCATE TABLE excel_import_batch;
TRUNCATE TABLE stocktake_scan;
TRUNCATE TABLE stocktake_diff;
TRUNCATE TABLE stocktake;
TRUNCATE TABLE operation_log;
TRUNCATE TABLE client_error;
TRUNCATE TABLE item;
TRUNCATE TABLE seq_item_code;
TRUNCATE TABLE sys_alert;

-- ---------------------------------------------------------------------
-- 1. 字典与 bench 操作员（幂等；字典若已由管理员配置则跳过）
-- ---------------------------------------------------------------------
INSERT INTO auction_venue (code, name)
SELECT v.code, v.name
FROM (SELECT 'HT' AS code, '名古屋会場' AS name UNION ALL
      SELECT 'MK', '東京モーターズ会場' UNION ALL
      SELECT 'EX', '関西エクスチェンジ会場') v
WHERE NOT EXISTS (SELECT 1 FROM auction_venue av WHERE av.code = v.code);

INSERT INTO price_band (code, lower_bound, upper_bound)
SELECT b.code, b.lo, b.hi
FROM (SELECT 'A' AS code, NULL AS lo, 1000   AS hi UNION ALL
      SELECT 'B', 1000,   3000   UNION ALL
      SELECT 'C', 3000,   10000  UNION ALL
      SELECT 'D', 10000,  30000  UNION ALL
      SELECT 'E', 30000,  100000 UNION ALL
      SELECT 'F', 100000, NULL) b
WHERE NOT EXISTS (SELECT 1 FROM price_band p WHERE p.code = b.code);

-- bench 操作员 4 名：既是 created_by/operator_id 的数值源，也是压测的登录账号池。
--
-- 为什么必须「可登录 + EDITOR」——D-081 的两条硬约束：
--   ① maximumSessions(8)（SecurityConfig）：单账号最多 8 个并发会话，第 9 个登录
--      把最早的踢掉（expiredSessionStrategy → 该会话此后每次请求 401）。而 docs/01
--      §4.2 要 10 并发录入——压测峰值 22 个 VU（browse 5 + search 5 + dashboard 2
--      + entry 10），**一个账号扛不住**。4 个账号 → 每账号 ≤6 会话，留余量。
--   ② role 必须 ≥ EDITOR(2)：entry 场景打 POST /api/items 与 /item-codes/preview，
--      二者都是 @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")——VIEWER(3) 必 403。
--
-- 密码 Kcgl-bench-2026（hash 由项目自己的 BCryptPasswordEncoder(12) 生成，非手拼）。
-- 本脚本会 TRUNCATE 业务表，只可能在压测库执行；生产部署不跑它（README 执行顺序）。
--
-- ON DUPLICATE KEY UPDATE：早期版本写的是占位 hash + enabled=0 + role=3，那是
-- 「insert-only 幂等」——重跑不自愈（D-080 决策 9 的同类教训）。这里改成 upsert，
-- 让旧库直接升级到可登录的压测账号池。
INSERT INTO sys_user (username, password_hash, display_name, role, locale, enabled)
VALUES
    ('bench-op1', '$2a$12$bx4xkssTPqIgUNtNUlwBceP6QvWPHsp/pW41FKsWt55KeHdC7PXM2', 'ベンチ演習1', 2, 'ja-JP', 1),
    ('bench-op2', '$2a$12$bx4xkssTPqIgUNtNUlwBceP6QvWPHsp/pW41FKsWt55KeHdC7PXM2', 'ベンチ演習2', 2, 'ja-JP', 1),
    ('bench-op3', '$2a$12$bx4xkssTPqIgUNtNUlwBceP6QvWPHsp/pW41FKsWt55KeHdC7PXM2', 'ベンチ演習3', 2, 'ja-JP', 1),
    ('bench-op4', '$2a$12$bx4xkssTPqIgUNtNUlwBceP6QvWPHsp/pW41FKsWt55KeHdC7PXM2', 'ベンチ演習4', 2, 'ja-JP', 1) AS new
ON DUPLICATE KEY UPDATE
    password_hash = new.password_hash,
    display_name  = new.display_name,
    role          = new.role,
    enabled       = new.enabled;

-- ---------------------------------------------------------------------
-- 2. item 主表 5 万件
-- ---------------------------------------------------------------------
INSERT INTO item
    (item_code, venue_id, venue_code, buy_month, seq_prefix, seq_no, buy_date,
     photo_date, purchase_price, fee, shipping_fee, tax, sold_price,
     price_band_code, warehouse, shelf_no, warehouse_in_date, group_no, remark,
     item_name, category, author_kiln, size_text, weight_g, sales_channel,
     stock_status, sale_status, voided, deleted, created_by, created_at, version)
SELECT
    CONCAT(b.vcode, MONTH(b.bdate), '-', b.seq_prefix, b.seq_no, b.band_code),
    b.venue_id, b.vcode, MONTH(b.bdate), b.seq_prefix, b.seq_no, b.bdate,
    b.photo_date, b.price, b.fee, b.shipping_fee, b.tax, b.sold_price,
    b.band_code,
    -- 当前仓：在库调拨件=对侧仓（与第 5 节 TRANSFER 终态一致）；
    -- 在途/已出库保留录入预定仓（出库后 warehouse 列语义=最后所在仓）
    CASE WHEN b.stock_status = 1 AND b.has_transfer THEN 3 - b.wh ELSE b.wh END,
    b.shelf_no, b.in_date, b.group_no, b.remark,
    b.item_name, b.category, b.author_kiln, b.size_text, b.weight_g, b.sales_channel,
    b.stock_status, b.sale_status, 0, 0, b.op_id, b.created_at, 0
FROM (
    SELECT c.*, lb.blk,
        -- 桶内序（ORDER BY n 确定性）：流水 1..99 循环，块号每 99 进位一次；
        -- 前缀由块号按 base-26 递进（A..Z / AA..AZ / BA..，同 ItemCodeFormatter）。
        -- 不可用 CHAR(64 + 块号)：块号 ≥27 会溢出到 ASCII 91..96 与 a-z，而
        -- item_code 的排序规则是 utf8mb4_0900_ai_ci（大小写不敏感）→ 'a'≡'A'
        -- 与同桶低位块撞 uk_item_code（实测 HT5-a9B ↔ HT5-A9B），且小写/符号
        -- 前缀本就不满足管理号形态 [A-Z]{1,3}（搜索/解析路径必挂，D-080）。
        CASE WHEN lb.blk < 26 THEN CHAR(65 + lb.blk)
             WHEN lb.blk < 702 THEN CONCAT(CHAR(65 + (lb.blk - 26) DIV 26),
                                           CHAR(65 + (lb.blk - 26) MOD 26))
             ELSE CONCAT(CHAR(65 + (lb.blk - 702) DIV 676),
                         CHAR(65 + ((lb.blk - 702) DIV 26) MOD 26),
                         CHAR(65 + (lb.blk - 702) MOD 26))
        END                                 AS seq_prefix,
        MOD(c.rn - 1, 99) + 1               AS seq_no,
        -- 价格档位快照（映射与 §1 档位种子一致）
        CASE WHEN c.price <   1000 THEN 'A'
             WHEN c.price <   3000 THEN 'B'
             WHEN c.price <  10000 THEN 'C'
             WHEN c.price <  30000 THEN 'D'
             WHEN c.price < 100000 THEN 'E'
             ELSE 'F' END                   AS band_code,
        -- 销售终态：在库上架件=1在售；卖出系=2成交；报废/退回会场=3取消
        CASE WHEN c.stock_status = 0 THEN 0
             WHEN c.stock_status = 1 THEN (CASE WHEN c.has_listup THEN 1 ELSE 0 END)
             ELSE (CASE WHEN c.out_sub IN (0, 2) THEN 2 ELSE 3 END) END AS sale_status,
        -- 入库日期（在途=NULL）；偏移盐与 §5 ARRIVE 同源（'lag2'）——
        -- stats monthInbound 与 ledger 到仓时间同口径
        CASE WHEN c.stock_status = 0 THEN NULL
             ELSE DATE_ADD(c.bdate, INTERVAL (2 + FLOOR(CRC32(CONCAT('lag2', c.n)) / 4294967296 * 8)) DAY) END AS in_date,
        -- 拍照日：有图且 95% 填
        CASE WHEN c.img_cnt > 0 AND c.r_photo < 0.95
             THEN DATE_ADD(c.bdate, INTERVAL 1 DAY) ELSE NULL END          AS photo_date,
        -- 售价：仅卖出系已出库件（纯卖出/退货重卖终态都成交）
        CASE WHEN c.stock_status = 2 AND c.out_sub IN (0, 2)
             THEN LEAST(99999999, ROUND(c.price * (0.6 + c.r_sold * 1.4)))
             ELSE NULL END                                                     AS sold_price,
        -- 杂费（fee 30% / shipping 50% / tax 20%）
        CASE WHEN c.r_fee < 0.30 THEN 100 + FLOOR(c.r_fee2 * 900) ELSE NULL END AS fee,
        CASE WHEN c.r_fee < 0.50 THEN 200 + FLOOR(c.r_fee2 * 800) ELSE NULL END AS shipping_fee,
        CASE WHEN c.r_fee < 0.20 THEN 50  + FLOOR(c.r_fee2 * 450) ELSE NULL END AS tax,
        -- 检索面：扩展属性 40%、组号 5%、备注 60% 均长 ~120 字（LIKE 压测语料）
        CASE WHEN c.r_ext < 0.40 THEN
            ELT(1 + (c.n * 7 + 3) % 20,
                '古伊万里染付皿','備前焼花入','九谷焼茶碗','有田焼徳利','京焼香炉',
                '瀬戸黒茶碗','信楽焼壺','萩焼湯呑','唐津焼向付','清水焼置物',
                '玳玻皿','ガラス浮玉','銅製花器','鉄瓶','南部鉄器',
                '木彫置物','象牙簪','珊瑚ブローチ','時計懐中','銀杯')
        ELSE NULL END                        AS item_name,
        CASE WHEN c.r_ext < 0.40 THEN
            ELT(1 + (c.n * 5 + 1) % 8,
                '陶磁器','茶道具','金属工芸','ガラス器','木工芸','象牙珊瑚','西洋美術','雑貨')
        ELSE NULL END                        AS category,
        CASE WHEN c.r_ext < 0.40 THEN
            ELT(1 + (c.n * 3 + 2) % 10,
                '作者不詳','五代角倉','六代清水','人間国宝系','無名作家','窯元不詳',
                '佐々木窯','山田窯','藤原窯','中村窯')
        ELSE NULL END                        AS author_kiln,
        CASE WHEN c.r_ext < 0.40
             THEN CONCAT('高さ', 5 + c.n % 40, 'cm 幅', 4 + c.n % 25, 'cm')
        ELSE NULL END                        AS size_text,
        CASE WHEN c.r_ext < 0.40 THEN 100 + (c.n * 37) % 9000 ELSE NULL END AS weight_g,
        CASE WHEN c.r_ext < 0.40 THEN 'ヤフオク' ELSE NULL END             AS sales_channel,
        CASE WHEN c.r_ext < 0.05 THEN CONCAT('G2026-', LPAD(c.n % 10000, 4, '0')) ELSE NULL END AS group_no,
        CASE WHEN c.r_remark < 0.60 THEN
            LEFT(REPEAT('棚卸し済み。状態良好。ノークレーム・ノーリターン。限定品。付属品揃い。', 6),
                 118 + c.n % 9)
        ELSE NULL END                        AS remark,
        -- 在库件给货架位；出库/在途不给（仓库外）
        CASE WHEN c.stock_status = 1
             THEN CONCAT('R', LPAD(1 + c.n % 90, 3, '0'), '-', 1 + c.n % 20)
        ELSE NULL END                        AS shelf_no,
        -- 录入时刻：买入日 8-18 点（ledger CREATE 同刻）
        DATE_ADD(TIMESTAMP(c.bdate), INTERVAL (8 + FLOOR(c.r_ct * 10)) HOUR) AS created_at,
        -- 操作员轮转
        ELT(1 + c.n % 3, c.op1, c.op2, c.op3) AS op_id
    FROM (
        SELECT s.n,
            CASE WHEN CRC32(CONCAT('v', s.n)) / 4294967296 < 0.60 THEN 'HT'
                 WHEN CRC32(CONCAT('v', s.n)) / 4294967296 < 0.85 THEN 'MK'
                 ELSE 'EX' END                                AS vcode,
            CASE WHEN CRC32(CONCAT('v', s.n)) / 4294967296 < 0.60 THEN vHT.id
                 WHEN CRC32(CONCAT('v', s.n)) / 4294967296 < 0.85 THEN vMK.id
                 ELSE vEX.id END                              AS venue_id,
            -- 18 个月固定窗口（542 天 → 2025-04-01..2026-09-25）
            DATE_ADD(DATE '2025-04-01',
                     INTERVAL FLOOR(CRC32(CONCAT('d', s.n)) / 4294967296 * 542) DAY) AS bdate,
            -- 状态 10/55/35；已出库子型 0卖出/1报废/2退货重卖/3退回会场（60/20/12/8）
            CASE WHEN CRC32(CONCAT('s', s.n)) / 4294967296 < 0.10 THEN 0
                 WHEN CRC32(CONCAT('s', s.n)) / 4294967296 < 0.65 THEN 1
                 ELSE 2 END                                   AS stock_status,
            CASE WHEN CRC32(CONCAT('o', s.n)) / 4294967296 < 0.60 THEN 0
                 WHEN CRC32(CONCAT('o', s.n)) / 4294967296 < 0.80 THEN 1
                 WHEN CRC32(CONCAT('o', s.n)) / 4294967296 < 0.92 THEN 2
                 ELSE 3 END                                   AS out_sub,
            (CRC32(CONCAT('t', s.n)) / 4294967296 < 0.30)     AS has_transfer,
            (CRC32(CONCAT('l', s.n)) / 4294967296 < 0.40)     AS has_listup,
            CASE WHEN CRC32(CONCAT('w', s.n)) / 4294967296 < 0.70 THEN 1 ELSE 2 END AS wh,
            FLOOR(POWER(10, 2.5 + CRC32(CONCAT('p', s.n)) / 4294967296 * 2.5)) AS price,
            CASE WHEN CRC32(CONCAT('i', s.n)) / 4294967296 < 0.39 THEN 0
                 WHEN CRC32(CONCAT('i', s.n)) / 4294967296 < 0.50 THEN 1
                 WHEN CRC32(CONCAT('i', s.n)) / 4294967296 < 0.59 THEN 2
                 WHEN CRC32(CONCAT('i', s.n)) / 4294967296 < 0.68 THEN 3
                 WHEN CRC32(CONCAT('i', s.n)) / 4294967296 < 0.76 THEN 4
                 WHEN CRC32(CONCAT('i', s.n)) / 4294967296 < 0.83 THEN 5
                 WHEN CRC32(CONCAT('i', s.n)) / 4294967296 < 0.88 THEN 6
                 WHEN CRC32(CONCAT('i', s.n)) / 4294967296 < 0.92 THEN 7
                 WHEN CRC32(CONCAT('i', s.n)) / 4294967296 < 0.95 THEN 8
                 ELSE 9 END                                   AS img_cnt,
            CRC32(CONCAT('photo',  s.n)) / 4294967296 AS r_photo,
            CRC32(CONCAT('sold',   s.n)) / 4294967296 AS r_sold,
            CRC32(CONCAT('fee',    s.n)) / 4294967296 AS r_fee,
            CRC32(CONCAT('fee2',   s.n)) / 4294967296 AS r_fee2,
            CRC32(CONCAT('imgd',   s.n)) / 4294967296 AS r_img,
            CRC32(CONCAT('remark', s.n)) / 4294967296 AS r_remark,
            CRC32(CONCAT('ext',    s.n)) / 4294967296 AS r_ext,
            CRC32(CONCAT('ct',     s.n)) / 4294967296 AS r_ct,
            u1.id AS op1, u2.id AS op2, u3.id AS op3,
            ROW_NUMBER() OVER (
                PARTITION BY CASE WHEN CRC32(CONCAT('v', s.n)) / 4294967296 < 0.60 THEN vHT.id
                                  WHEN CRC32(CONCAT('v', s.n)) / 4294967296 < 0.85 THEN vMK.id
                                  ELSE vEX.id END,
                                 MONTH(DATE_ADD(DATE '2025-04-01',
                                        INTERVAL FLOOR(CRC32(CONCAT('d', s.n)) / 4294967296 * 542) DAY))
                ORDER BY s.n) AS rn
        FROM (WITH RECURSIVE seq(n) AS (
                  SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 50000
              ) SELECT n FROM seq) s
        CROSS JOIN (SELECT id FROM auction_venue WHERE code = 'HT') vHT
        CROSS JOIN (SELECT id FROM auction_venue WHERE code = 'MK') vMK
        CROSS JOIN (SELECT id FROM auction_venue WHERE code = 'EX') vEX
        CROSS JOIN (SELECT id FROM sys_user WHERE username = 'bench-op1') u1
        CROSS JOIN (SELECT id FROM sys_user WHERE username = 'bench-op2') u2
        CROSS JOIN (SELECT id FROM sys_user WHERE username = 'bench-op3') u3
    ) c
    -- 块号=桶内第几个 99 块（0 起）；LATERAL 让前缀表达式复用而不必抄三遍
    JOIN LATERAL (SELECT FLOOR((c.rn - 1) / 99) AS blk) lb ON TRUE
) b;

-- ---------------------------------------------------------------------
-- 3. seq_item_code 计数器回填（窗口序=CHAR_LENGTH 优先再字典序，V3 同款：
--    每桶位置序最大前缀及其下最大流水——后续真实录入从其后继续）
-- ---------------------------------------------------------------------
INSERT INTO seq_item_code (venue_id, month, cur_prefix, cur_seq, updated_at)
SELECT venue_id, buy_month, seq_prefix, max_seq, NOW(3)
FROM (
    SELECT venue_id, buy_month, seq_prefix, MAX(seq_no) AS max_seq,
           ROW_NUMBER() OVER (
               PARTITION BY venue_id, buy_month
               ORDER BY CHAR_LENGTH(seq_prefix) DESC, seq_prefix DESC
           ) AS rn
    FROM item
    GROUP BY venue_id, buy_month, seq_prefix
) ranked
WHERE rn = 1;

-- ---------------------------------------------------------------------
-- 4. item_image（~12.5 万行；路径 '{yyyy}/{MM}/{uuid}.jpg' 与 ImageStore
--    约定一致；文件本体由 bench-images.sh 硬链接落盘）
-- ---------------------------------------------------------------------
INSERT INTO item_image (item_id, client_uuid, stored_path, thumb_path, image_type, sort_order, created_by, created_at)
SELECT i.id, UUID(),
       CONCAT(DATE_FORMAT(i.created_at, '%Y/%m'), '/', UUID(), '.jpg'),
       CONCAT(DATE_FORMAT(i.created_at, '%Y/%m'), '/', UUID(), '_t.jpg'),
       1, k.k - 1, i.created_by,
       DATE_ADD(i.created_at, INTERVAL k.k MINUTE)
FROM item i
JOIN LATERAL (SELECT 1 AS k UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL
              SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) k
  ON k.k <= CASE WHEN CRC32(CONCAT('i', i.id)) / 4294967296 < 0.39 THEN 0
                 WHEN CRC32(CONCAT('i', i.id)) / 4294967296 < 0.50 THEN 1
                 WHEN CRC32(CONCAT('i', i.id)) / 4294967296 < 0.59 THEN 2
                 WHEN CRC32(CONCAT('i', i.id)) / 4294967296 < 0.68 THEN 3
                 WHEN CRC32(CONCAT('i', i.id)) / 4294967296 < 0.76 THEN 4
                 WHEN CRC32(CONCAT('i', i.id)) / 4294967296 < 0.83 THEN 5
                 WHEN CRC32(CONCAT('i', i.id)) / 4294967296 < 0.88 THEN 6
                 WHEN CRC32(CONCAT('i', i.id)) / 4294967296 < 0.92 THEN 7
                 WHEN CRC32(CONCAT('i', i.id)) / 4294967296 < 0.95 THEN 8
                 ELSE 9 END;

-- ---------------------------------------------------------------------
-- 5. stock_ledger（~14 万行；动作全部走 InventoryStateMachine 合法边）
--
--    动作矩阵（终态 ↔ item 快照一致；子型判定与 §2 同盐同值）：
--      在途          CREATE                                      [0,0]
--      在库          CREATE, ARRIVE                              [1,0] wh=预定
--      在库·上架     +LIST_UP                                    [1,1]
--      在库·调拨     +TRANSFER(预定仓→对侧)                      [1,x] wh=对侧
--      出库·卖出     CREATE, ARRIVE, SELL                        [2,2]
--      出库·报废     CREATE, ARRIVE, SCRAP                       [2,3]
--      出库·退货重卖 CREATE, ARRIVE, SELL, RETURN(顾客), SELL    [2,2]
--      出库·退回会场 CREATE, ARRIVE, RETURN_VENUE                [2,3]
--
--    头寸对账（restore.sh DRIFT_SQL 同口径）：每件净头寸=其在库仓 +1、
--    其余仓 0；已出库/在途全 0。录入预定仓 w0：在库调拨件因 item.warehouse
--    已写对侧仓，w0=3-warehouse 还原；其余件 w0=warehouse。
--    时间链：动作时刻=录入时刻+分段偏移（段间隔互不重叠保证同件单调：
--    ARRIVE≤10 < LIST_UP 11..25 < TRANSFER 26..60 < 主出库 61..350 <
--    RETURN 351..380 < 再SELL 381..470）；晚录入件 LEAST 封顶
--    2026-09-25 17:00 防未来时间。全局 ORDER BY 令 id 与时间同向。
-- ---------------------------------------------------------------------
INSERT INTO stock_ledger
    (txn_type, item_id, item_code, stock_from, stock_to, sale_from, sale_to,
     wh_from, wh_to, qty_change, reason, return_direction,
     operator_id, operator_name, created_at)
SELECT txn, item_id, item_code, stock_from, stock_to, sale_from, sale_to,
       wh_from, wh_to, qty, reason, ret_dir, operator_id, operator_name, act_time
FROM (
    -- ① CREATE：全部件（stock 0在途、sale 0未上架；wh 双侧 NULL 不入账）
    SELECT 1 AS txn, i.id AS item_id, i.item_code,
           NULL AS stock_from, 0 AS stock_to, NULL AS sale_from, 0 AS sale_to,
           NULL AS wh_from, NULL AS wh_to, 0 AS qty,
           NULL AS reason, NULL AS ret_dir,
           i.created_by AS operator_id, u.display_name AS operator_name,
           i.created_at AS act_time
    FROM item i JOIN sys_user u ON u.id = i.created_by

    UNION ALL
    -- ② ARRIVE：在库+已出库（在途未到仓）；wh NULL→预定仓
    SELECT 2, i.id, i.item_code,
           0, 1, NULL, NULL,
           NULL,
           CASE WHEN i.stock_status = 1 AND CRC32(CONCAT('t', i.id)) / 4294967296 < 0.30
                THEN 3 - i.warehouse ELSE i.warehouse END,
           1, NULL, NULL,
           i.created_by, u.display_name,
           LEAST(DATE_ADD(DATE_ADD(i.created_at, INTERVAL (2 + FLOOR(CRC32(CONCAT('lag2', i.id)) / 4294967296 * 8)) DAY),
                          INTERVAL (9 + FLOOR(CRC32(CONCAT('h2', i.id)) / 4294967296 * 8)) HOUR),
                 TIMESTAMP '2026-09-25 17:00:00')
    FROM item i JOIN sys_user u ON u.id = i.created_by
    WHERE i.stock_status > 0

    UNION ALL
    -- ③ LIST_UP：在库且终态在售（sale 0→1，实物未动 wh 双 NULL）
    SELECT 9, i.id, i.item_code,
           NULL, NULL, 0, 1,
           NULL, NULL, 0, NULL, NULL,
           i.created_by, u.display_name,
           LEAST(DATE_ADD(DATE_ADD(i.created_at, INTERVAL (11 + FLOOR(CRC32(CONCAT('lag9', i.id)) / 4294967296 * 14)) DAY),
                          INTERVAL (9 + FLOOR(CRC32(CONCAT('h9', i.id)) / 4294967296 * 8)) HOUR),
                 TIMESTAMP '2026-09-25 17:00:00')
    FROM item i JOIN sys_user u ON u.id = i.created_by
    WHERE i.stock_status = 1 AND i.sale_status = 1

    UNION ALL
    -- ④ TRANSFER：在库调拨件（同盐复算 30%）；wh 预定仓→对侧
    SELECT 5, i.id, i.item_code,
           NULL, NULL, NULL, NULL,
           3 - i.warehouse, i.warehouse, 0,
           NULL, NULL,
           i.created_by, u.display_name,
           LEAST(DATE_ADD(DATE_ADD(i.created_at, INTERVAL (26 + FLOOR(CRC32(CONCAT('lag5', i.id)) / 4294967296 * 34)) DAY),
                          INTERVAL (9 + FLOOR(CRC32(CONCAT('h5', i.id)) / 4294967296 * 8)) HOUR),
                 TIMESTAMP '2026-09-25 17:00:00')
    FROM item i JOIN sys_user u ON u.id = i.created_by
    WHERE i.stock_status = 1 AND CRC32(CONCAT('t', i.id)) / 4294967296 < 0.30

    UNION ALL
    -- ⑤ 主出库动作：已出库件按子型（0卖出 SELL / 1报废 SCRAP / 3退回会场 RETURN_VENUE）
    SELECT CASE WHEN CRC32(CONCAT('o', i.id)) / 4294967296 < 0.60 THEN 3
                WHEN CRC32(CONCAT('o', i.id)) / 4294967296 < 0.80 THEN 4
                ELSE 6 END,
           i.id, i.item_code,
           1, 2, NULL,
           CASE WHEN CRC32(CONCAT('o', i.id)) / 4294967296 < 0.60 THEN 2 ELSE 3 END,
           i.warehouse, NULL, -1,
           CASE WHEN CRC32(CONCAT('o', i.id)) / 4294967296 BETWEEN 0.60 AND 0.80
                THEN '経年劣化のため処分' END,
           CASE WHEN CRC32(CONCAT('o', i.id)) / 4294967296 >= 0.92 THEN 2 END,
           i.created_by, u.display_name,
           LEAST(DATE_ADD(DATE_ADD(i.created_at, INTERVAL (61 + FLOOR(CRC32(CONCAT('lagO', i.id)) / 4294967296 * 289)) DAY),
                          INTERVAL (9 + FLOOR(CRC32(CONCAT('hO', i.id)) / 4294967296 * 8)) HOUR),
                 TIMESTAMP '2026-09-25 17:00:00')
    FROM item i JOIN sys_user u ON u.id = i.created_by
    WHERE i.stock_status = 2 AND (CRC32(CONCAT('o', i.id)) / 4294967296 < 0.60
                               OR CRC32(CONCAT('o', i.id)) / 4294967296 BETWEEN 0.60 AND 0.80
                               OR CRC32(CONCAT('o', i.id)) / 4294967296 >= 0.92)

    UNION ALL
    -- ⑥ 退货重卖件（子型 2）：SELL₁ → RETURN(顾客退回) → SELL₂ 三行
    SELECT 3, i.id, i.item_code,
           1, 2, NULL, 2,
           i.warehouse, NULL, -1, NULL, NULL,
           i.created_by, u.display_name,
           LEAST(DATE_ADD(DATE_ADD(i.created_at, INTERVAL (61 + FLOOR(CRC32(CONCAT('lagA', i.id)) / 4294967296 * 289)) DAY),
                          INTERVAL (9 + FLOOR(CRC32(CONCAT('hA', i.id)) / 4294967296 * 8)) HOUR),
                 TIMESTAMP '2026-09-25 17:00:00')
    FROM item i JOIN sys_user u ON u.id = i.created_by
    WHERE i.stock_status = 2 AND CRC32(CONCAT('o', i.id)) / 4294967296 BETWEEN 0.80 AND 0.92

    UNION ALL
    SELECT 6, i.id, i.item_code,
           2, 1, 2, 3,
           NULL, i.warehouse, 1,
           '客様都合による返品', 1,
           i.created_by, u.display_name,
           LEAST(DATE_ADD(DATE_ADD(i.created_at, INTERVAL (351 + FLOOR(CRC32(CONCAT('lagB', i.id)) / 4294967296 * 29)) DAY),
                          INTERVAL (9 + FLOOR(CRC32(CONCAT('hB', i.id)) / 4294967296 * 8)) HOUR),
                 TIMESTAMP '2026-09-25 17:00:00')
    FROM item i JOIN sys_user u ON u.id = i.created_by
    WHERE i.stock_status = 2 AND CRC32(CONCAT('o', i.id)) / 4294967296 BETWEEN 0.80 AND 0.92

    UNION ALL
    SELECT 3, i.id, i.item_code,
           1, 2, NULL, 2,
           i.warehouse, NULL, -1, NULL, NULL,
           i.created_by, u.display_name,
           LEAST(DATE_ADD(DATE_ADD(i.created_at, INTERVAL (381 + FLOOR(CRC32(CONCAT('lagC', i.id)) / 4294967296 * 89)) DAY),
                          INTERVAL (9 + FLOOR(CRC32(CONCAT('hC', i.id)) / 4294967296 * 8)) HOUR),
                 TIMESTAMP '2026-09-25 17:00:00')
    FROM item i JOIN sys_user u ON u.id = i.created_by
    WHERE i.stock_status = 2 AND CRC32(CONCAT('o', i.id)) / 4294967296 BETWEEN 0.80 AND 0.92
) e
ORDER BY act_time, item_id;
