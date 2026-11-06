-- ========================================
-- V7：model_provider.provider_uid 唯一性从「唯一索引」收敛为「具名唯一约束」（PostgreSQL 路径）
-- ========================================
-- 语义（与 h2/V7__model_provider_uid_unique_constraint.sql 一致）：
--   * V3 用 create unique index 建了 uk_model_provider_uid（唯一索引）；
--   * 实体侧 Hibernate 现按具名表级唯一约束建表（uk_model_provider_uid）；
--   * 本脚本把迁移侧收敛为同名 UNIQUE 约束（先删 V3 的唯一索引，再加同名约束）。
--
-- 幂等性：drop index if exists + DO 块判重，可重复执行。
-- ========================================

drop index if exists uk_model_provider_uid;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uk_model_provider_uid'
          AND conrelid = 'model_provider'::regclass
    ) THEN
        ALTER TABLE model_provider ADD CONSTRAINT uk_model_provider_uid UNIQUE (provider_uid);
    END IF;
END $$;
