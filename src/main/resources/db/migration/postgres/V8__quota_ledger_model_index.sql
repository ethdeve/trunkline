-- ========================================
-- V8：补齐 quota_ledger.model 索引（PostgreSQL 路径）
-- ========================================
-- 语义（与 h2/V8__quota_ledger_model_index.sql 一致）：
--   * 实体 QuotaLedgerEntity 声明了 @Index(name = "idx_quota_ledger_model", columnList = "model")，
--     但 V1/V5 迁移脚本从未建过该索引，本脚本补齐。
--
-- 幂等性：create index if not exists，可重复执行。
-- ========================================

create index if not exists idx_quota_ledger_model on quota_ledger (model);
