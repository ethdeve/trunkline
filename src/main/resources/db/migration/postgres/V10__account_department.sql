-- ========================================
-- V10：账号部门字段（jwt_accounts.department）（Postgres 路径，v3.5 Trunkline UI 改造）
-- ========================================
-- 语义（与 h2/V10__account_department.sql 一致）：
--   * 给账号表加 department 列：员工所属部门（如「研发部」「市场部」）。
--   * 用途：「团队用量」按全公司或某个部门维度统计——
--     token_usage.user_id 关联 jwt_accounts.username 取 department 后聚合。
--   * 历史数据留空（null）：聚合时归入「未分配部门」，不丢数据。
--
-- 幂等性：alter table ... add column if not exists，可重复执行。
-- ========================================

alter table jwt_accounts add column if not exists department varchar(128);

-- 部门维度聚合的连接键：token_usage.user_id = jwt_accounts.username
create index if not exists idx_jwt_accounts_username on jwt_accounts (username);
