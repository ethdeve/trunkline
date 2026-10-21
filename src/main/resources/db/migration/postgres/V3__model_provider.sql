-- ========================================
-- V3：供应商层（model_provider）+ service_instance.provider_id（PostgreSQL 路径，v2.11.0）
-- ========================================
-- 语义（与 h2/V3__model_provider.sql 一致，物理类型按 PG 取）：
--   * 新建 model_provider 表：把原先每加一个模型实例都要重填的 baseUrl / path /
--     adapter / 上游 API Key 收敛到供应商一行；
--   * service_instance 新增 provider_id（可空外联列）：实例创建时带 providerId
--     即从供应商带出地址与 Authorization 头；老实例为 NULL，行为不变。
--
-- 幂等性：全部语句为「已满足即空操作」（IF NOT EXISTS），可重复执行。
-- ========================================

create table if not exists model_provider (
    sort_order integer,
    created_at timestamp(6),
    id bigserial primary key,
    updated_at timestamp(6),
    adapter varchar(128),
    api_key varchar(1024),
    base_url varchar(512) not null,
    description varchar(512),
    models_path varchar(512),
    name varchar(128) not null,
    path varchar(512),
    provider_uid varchar(64),
    service_type varchar(64) not null,
    status varchar(32)
);

create unique index if not exists uk_model_provider_uid on model_provider (provider_uid);

alter table service_instance add column if not exists provider_id bigint;

create index if not exists idx_service_instance_provider_id on service_instance (provider_id);
