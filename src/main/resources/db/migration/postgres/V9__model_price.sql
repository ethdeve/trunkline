-- ========================================
-- V9：模型价格表（model_price）（PostgreSQL 路径，v3.5 Trunkline UI 改造）
-- ========================================
-- 语义（与 h2/V9__model_price.sql 一致，物理类型按 PG 取）：
--   * 新建 model_price 表：为每个模型（含虚拟模型名）配置 input/output 单价（¥ / 千 token），
--     供「我的账单」按 token × 价格 聚合。价格来源一期手工配置（用户决策 #2）。
--   * 唯一键：model_name（模型名 / 虚拟模型名即路由到的对外名）。
--   * 单价默认 0：未配置价格的模型按 ¥0 计入，账单页给空态提示。
--
-- 幂等性：create table if not exists，可重复执行。
-- ========================================

create table if not exists model_price (
    id bigserial primary key,
    model_name varchar(255) not null,
    service_type varchar(64),
    price_input numeric(12, 6) not null default 0,
    price_output numeric(12, 6) not null default 0,
    currency varchar(8) not null default 'CNY',
    enabled boolean not null default true,
    description varchar(512),
    created_at timestamp(6),
    updated_at timestamp(6)
);

create unique index if not exists uk_model_price_model_name on model_price (model_name);
create index if not exists idx_model_price_service_type on model_price (service_type);
