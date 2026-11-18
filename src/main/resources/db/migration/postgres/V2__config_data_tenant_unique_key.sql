-- 多租户：config_data 唯一键纳入 tenant_id。
--
-- V1 基线约束 uk_config_data_key_version (config_key, version) 在多租户下会让
-- 不同租户的同名同版本配置互斥：租户 B 写入 config_key=X, version=0 时会撞
-- 租户 A 已占位的唯一键。改为 (config_key, version, tenant_id) 后各租户可
-- 独立维护自己的配置版本；查询侧由实体 @Filter(tenantFilter) 自动附加
-- tenant_id 条件，upsert 不会跨租户串行。
--
-- 本版本无生产存量，V1 约束下不可能存在跨租户同名同版本行，
-- 故直接重建约束即可，无需数据清理。
alter table config_data drop constraint uk_config_data_key_version;
alter table config_data add constraint uk_config_data_key_version
    unique (config_key, version, tenant_id);
