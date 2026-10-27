-- ========================================
-- V5: quota_ledger 增加分组（模型分组 / 池）维度
-- 背景：v3.4 配额多维度改造把账本维度从「租户/Key/用户/服务类型/模型」五维
-- 扩到六维，新增 group_id（模型分组 / 池名）。
-- Hibernate 的 ddl-auto 只补缺失的列、不会修改已存在表的唯一约束，因此
-- 「唯一键由 7 列扩到 8 列」必须由本脚本显式完成（删旧约束 + 建新约束）。
--
-- 列语义：group_id 为空串哨兵值（与其它维度列一致，表示「未指定分组」）。
-- 存量行在加列时统一取 ''，不破坏原有唯一性（七列已唯一 ⇒ 加常量列后仍唯一）。
--
-- 幂等性：全部语句都是「已满足即空操作」，重复执行安全。
-- ========================================

-- A1：新增分组维度列（空串哨兵值，与其它维度列的 NOT NULL 约定一致）
alter table quota_ledger add column if not exists group_id varchar(255) not null default '';

-- A2：唯一约束由 7 列扩到 8 列（含 group_id）
alter table quota_ledger drop constraint if exists uk_quota_ledger_dimension_window;
alter table quota_ledger add constraint uk_quota_ledger_dimension_window
    unique (tenant_id, api_key_id, user_id, service_type, model, group_id, window_type, window_start);

-- A3：分组维度查询索引（按分组重置 / 按分组查用量走这条索引）
-- 索引名与实体 @Index(name = "idx_quota_ledger_group") 保持一致，避免 ddl-auto 另建一份同义索引
create index if not exists idx_quota_ledger_group on quota_ledger (group_id);
