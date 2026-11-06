-- ========================================
-- V8：补齐 quota_ledger.model 索引（H2 路径）
-- ========================================
-- 背景（X4，2026-10-28）：
--   * 实体 QuotaLedgerEntity 声明了 @Index(name = "idx_quota_ledger_model", columnList = "model")，
--     但 V1/V5 迁移脚本从未建过该索引 —— H2BaselineMatchesEntityModelTest 暴露的真实漂移：
--     迁移路径的库缺配额按模型查询的索引（ddl-auto=update 不会给已存在的表补索引）。
--   * V1 已发布不可改（Flyway 按校验和判定），故以 V8 增量补齐。
--
-- 幂等性：create index if not exists，可重复执行。
-- ========================================

create index if not exists idx_quota_ledger_model on quota_ledger (model);
