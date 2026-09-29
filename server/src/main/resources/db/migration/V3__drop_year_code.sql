-- =====================================================================
-- V3: 管理号去年代号（D-068，2026-09-29 甲方指示「跨年的编号不用在意，也不用体现年份」）
--
-- 格式 {会场2}{年代号}{月}-{前缀}{流水}{价格码} → {会场2}{月}-{前缀}{流水}{价格码}
-- （HTK9-A1X → HT9-A1X）。桶键同步去 year：(venue_id, month) 跨年连续——
-- 若桶仍含年，2026-09 与 2027-09 各自从 A1 起 → 生成号撞 uk_item_code。
--
-- 计数器不能裸删（每日自检断言按桶 cur_seq≥MAX(seq_no)，桶行丢失=IFNULL −1 仍报）：
-- 先删旧行、改表形，再从存量 item 按位置序重建每桶最大 (前缀, 流水)。
-- 重建含作废/软删件（号仍占用 uk 空间）；窗口序=CHAR_LENGTH 优先再字典序，
-- 与 ItemCodeFormatter.prefixRank 的进位序一致（A<Z<AA，纯字典序方向相反）。
-- =====================================================================

-- 1. 清空旧计数器（year 列 NOT NULL，新形 INSERT 无法携带年值，必须先改形再重建）
DELETE FROM seq_item_code;

-- 2. 桶表改形：去年列、唯一键改 (venue_id, month)（此时表已空，无跨年同月碰撞）
ALTER TABLE seq_item_code
    DROP COLUMN `year`,
    DROP INDEX uk_bucket,
    ADD UNIQUE KEY uk_bucket (venue_id, month);

-- 3. 从存量 item 重建计数器：每 (venue_id, buy_month) 取位置序最大的前缀及其最大流水
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

-- 4. 商品表去年份快照列（年代号字典一并退役）
ALTER TABLE item
    DROP COLUMN `year`,
    DROP COLUMN year_code;

-- 5. 年代号字典表退役（种子 2016=A..2041=Z 随表消失；A2 决策作废归档）
DROP TABLE year_code;
