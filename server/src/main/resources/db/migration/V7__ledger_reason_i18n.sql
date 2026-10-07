-- D-130：库存流水理由结构化（承接 V5/V6 的同一根因）。
-- stock_ledger.reason 里只有一处是后端生成的日文整句——盘点差异确认落
-- 「棚卸調整 PD2026100801」（StocktakeService），前端两处（商品详情取引履歴、
-- 管理端台帳）原样渲染，切语言纹丝不动；其余各处都是操作人现填的自由文本，
-- 语言无关，不在本次范围。
--
-- 处方同 V5/V6：写日文原文的同时补 reason_code（i18n 键）+ reason_params
-- （插值参数），前端按当前语言渲染；reason 列不动——它同时是历史行兜底与
-- 人工理由的载体，导出与日志按语言无关的原文消费。
-- 纯加法迁移（expand-contract）：只加可空列，不改既有列、不动数据。
ALTER TABLE stock_ledger
  ADD COLUMN reason_code VARCHAR(64) NULL COMMENT '系统生成理由的 i18n 键（NULL=人工理由或历史行）' AFTER reason,
  ADD COLUMN reason_params JSON NULL COMMENT '系统生成理由的插值参数' AFTER reason_code;
