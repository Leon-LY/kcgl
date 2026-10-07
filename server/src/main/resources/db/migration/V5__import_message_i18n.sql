-- =====================================================================
-- V5｜导入报告提示语结构化（D-127）
-- =====================================================================
-- 症状：导入报告的補注（まとめ売り「複数商品のため単価未分割」）与失败原因
-- 由后端拼成日文句子直接落库，前端原样渲染 → 切到中文/英文仍是日文。
--
-- 处方：与 ErrorCode（码+日文，前端 errors.<code> 覆盖）同构，在写日文的同时
-- 写入机器可读的「消息键 + 插值参数」；前端读回后按当前语言渲染，键缺失
-- （历史行）或三语未覆盖时回退原日文。
--
-- 纯加法迁移（expand-contract）：只加可空列，不改既有列、不动数据。
-- 行级错误（error_rows）本就是 JSON 列，其 code/params 直接进既有 JSON，无需 DDL。
-- =====================================================================

-- 1. 雅虎导入批次：note（まとめ売り補注）的结构化形态 + error_message 的消息键/参数
ALTER TABLE yahoo_import_batch
  ADD COLUMN note_json JSON NULL COMMENT 'note 的结构化形态 [{code,params,text}]' AFTER note,
  ADD COLUMN error_message_code VARCHAR(64) NULL COMMENT '失败消息键（前端按语言渲染）' AFTER error_message,
  ADD COLUMN error_message_params JSON NULL COMMENT '失败消息插值参数' AFTER error_message_code;

-- 2. Excel 导入批次：只有 error_message 需要结构化。
--    note（计数器跳变说明「HT-9 A5→A12」）是纯数据，无日文短语，不设 note_json。
ALTER TABLE excel_import_batch
  ADD COLUMN error_message_code VARCHAR(64) NULL COMMENT '失败消息键（前端按语言渲染）' AFTER error_message,
  ADD COLUMN error_message_params JSON NULL COMMENT '失败消息插值参数' AFTER error_message_code;
