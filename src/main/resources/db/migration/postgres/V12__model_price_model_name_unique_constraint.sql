-- ========================================
-- V12：model_price.model_name 唯一性从「唯一索引」收敛为「具名唯一约束」（PostgreSQL 路径）
-- ========================================
-- 语义（与 h2/V12__model_price_model_name_unique_constraint.sql 一致）：
--   * V9 用 create unique index 建了 uk_model_price_model_name（唯一索引）；
--   * 实体侧 Hibernate 按具名表级唯一约束建表（uk_model_price_model_name）；
--   * 本脚本把迁移侧收敛为同名 UNIQUE 约束（先删 V9 的唯一索引，再加同名约束）。
--
-- 幂等性：drop index if exists + DO 块判重，可重复执行。
-- ========================================

drop index if exists uk_model_price_model_name;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uk_model_price_model_name'
          AND conrelid = 'model_price'::regclass
    ) THEN
        ALTER TABLE model_price ADD CONSTRAINT uk_model_price_model_name UNIQUE (model_name);
    END IF;
END $$;
