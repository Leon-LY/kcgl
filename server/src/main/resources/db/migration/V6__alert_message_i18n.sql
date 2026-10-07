-- D-128：系统告警文案结构化（承接 V5 的同一根因）。
-- sys_alert.message 历史上只写日文句子，管理端原样渲染，切语言纹丝不动；
-- 现补 message_key（i18n 键）+ message_params（插值参数）。
-- message 列 NOT NULL 不动：它同时是旧行兜底、server log 与诊断包导出（DiagnosticsExport）的原文，
-- 三处都按语言无关的日文原文契约消费，故 code+params 是**并行新增**而非替换。
-- payload 仍是诊断明细（列表/样本/drift 等结构化证据），与文案键职责不同，不动。
ALTER TABLE sys_alert
  ADD COLUMN message_key VARCHAR(64) NULL COMMENT '文案 i18n 键（前端按语言渲染，NULL=历史行回退 message）' AFTER message,
  ADD COLUMN message_params JSON NULL COMMENT '文案插值参数' AFTER message_key;
