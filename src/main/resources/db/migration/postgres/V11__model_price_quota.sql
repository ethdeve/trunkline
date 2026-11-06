-- ========================================
-- V11：模型价格与额度合并表（model_price 加列）（postgres 路径，v3.5 Trunkline UI 改造）
-- ========================================
-- 语义（与 h2/V11__model_price_quota.sql 一致）：
--   * 需求：「价格与额度」与「额度与限速」复用一张表 —— 额度与限速是<针对厂商模型>的配置，
--     虚拟模型（原模型分组）有自己的额度与限速配置（落在 pool 定义上）。
--     故本表在价格列之外补齐与 PoolDefinition 同构的额度/限速列，
--     使「厂商模型」这一行既能配价格、也能配额度与限速。
--   * 行粒度 = 厂商模型名（= service_instance.instance_name，接入厂商时实例名直接取上游模型 ID）。
--   * 账单计价口径同步切到厂商模型名（token_usage.instance_name），本表主键即该名字。
--   * 限额语义与 PoolDefinition 一致：0 = 不限制；quota_alert_threshold 为告警比例（0.0-1.0）。
--
-- 幂等性：alter table ... add column if not exists，可重复执行。
-- ========================================

alter table model_price add column if not exists rate_limit_per_minute integer default 0;
alter table model_price add column if not exists hourly_request_limit bigint default 0;
alter table model_price add column if not exists hourly_token_limit bigint default 0;
alter table model_price add column if not exists daily_request_limit bigint default 0;
alter table model_price add column if not exists daily_token_limit bigint default 0;
alter table model_price add column if not exists monthly_request_limit bigint default 0;
alter table model_price add column if not exists monthly_token_limit bigint default 0;
alter table model_price add column if not exists quota_alert_threshold double precision default 0.8;
