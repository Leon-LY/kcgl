-- =====================================================================
-- V2: Excel 导入批次表（M4-⑤，docs/01 3.1）
-- 结构镜像 yahoo_import_batch（D-054 已验证的异步管线，D-058 B 复用）：
-- sha 幂等 / status 0处理中 1完成 2失败 / 四计数（总行/生成号/旧号导入/错误）
-- / error_rows 采样前 1000 / note 记录计数器跳变说明（D-058 D 位置序推进）
-- 纯加法迁移：新建表，不动任何既有表（expand-contract 纪律）
-- =====================================================================
CREATE TABLE excel_import_batch (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  file_sha256 CHAR(64) NOT NULL COMMENT '同文件重复上传 409',
  original_filename VARCHAR(255) NOT NULL,
  status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0处理中 1完成 2失败',
  row_count INT UNSIGNED NOT NULL DEFAULT 0,
  generated_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '批量生成模式行数（管理番号列为空）',
  imported_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '旧号导入模式行数（管理番号列有值）',
  error_count INT UNSIGNED NOT NULL DEFAULT 0,
  error_rows JSON NULL COMMENT '采样:前1000条+计数',
  note VARCHAR(500) NULL COMMENT '计数器跳变说明（D-058 D 位置序推进）',
  error_message VARCHAR(500) NULL,
  uploaded_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3) NULL,
  UNIQUE KEY uk_sha (file_sha256),
  CONSTRAINT chk_excel_batch_status CHECK (status IN (0,1,2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Excel导入批次';
