-- ========================================
-- V16：业务表预埋 tenant_id 列（多租户数据层预埋，S2 任务 35）
-- ========================================
-- 原则（社区版完善规划 → 「社区版 = 商业版特例」）：
--   社区版与商业版共用同一份二进制与库表结构。商业版解锁多租户时
--   不能再做数据搬迁——所以租户列必须在社区版阶段就预埋。
--
-- 本迁移内容：
--   为 8 张核心业务表增加 tenant_id（VARCHAR(64) NOT NULL DEFAULT 'default'），
--   并建立普通索引。存量行自动归属 default 租户，社区版行为完全不变。
--
-- 范围说明：
--   * 仅覆盖「业务数据表」（账号/密钥/用量/审计/上游/配置）；
--     熔断、限流、异常统计等运维指标表不区分租户，不在本批。
--   * quota_ledger 不在本批：其 tenant_id 已在 V1 基线存在，但语义是
--     「配额维度」（空串哨兵，见 QuotaDimension#SENTINEL），与「租户归属」
--     不是一回事；商业版多租户下配额维度天然携带租户 ID，无需改动。
--   * provider（model_provider）的平台级 / 租户私有区分（'system' vs 租户 ID）
--     归 ENT-MVP 数据梳理（迭代计划任务 38），社区版所有行均为 'default'。
--   * 行级过滤开关 trunkline.tenant.filter-enabled 默认 false，
--     由 TenantJpaFilter 在商业版启用（S2 任务 36）。
-- ========================================

ALTER TABLE jwt_accounts ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE token_usage ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE api_call_history ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE security_audit ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE model_provider ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE config_data ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE service_config ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';

CREATE INDEX idx_jwt_accounts_tenant ON jwt_accounts (tenant_id);
CREATE INDEX idx_token_usage_tenant ON token_usage (tenant_id);
CREATE INDEX idx_call_history_tenant ON api_call_history (tenant_id);
CREATE INDEX idx_audit_tenant ON security_audit (tenant_id);
CREATE INDEX idx_model_provider_tenant ON model_provider (tenant_id);
CREATE INDEX idx_config_data_tenant ON config_data (tenant_id);
CREATE INDEX idx_service_config_tenant ON service_config (tenant_id);
