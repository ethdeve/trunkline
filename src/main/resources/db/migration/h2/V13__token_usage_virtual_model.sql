-- ========================================
-- V13：token_usage 增加虚拟模型（模型分组）维度（H2 路径，v3.5 Trunkline UI 改造）
-- ========================================
-- 语义（与 postgres/V13__token_usage_virtual_model.sql 一致）：
--   * 给 token_usage 加 virtual_model 列：请求命中的<b>虚拟模型名</b>（模型分组 / 池名，
--     如「公司主力」「便宜日常」），即员工在工具里填的 model 名。
--   * 用途：「团队用量」按虚拟模型维度统计用量与花费（设计规格 §5「团队用量」要求
--     「虚拟模型用量排行」），并支持下钻到实际落到的厂商模型。
--   * <b>与 instance_name 的分工</b>：
--       instance_name  = 实际路由到的厂商模型实例（计价依据，对齐 model_price.model_name）
--       virtual_model = 请求命中的虚拟模型（归属依据，员工可见的对外名字）
--     一次请求只命中一个虚拟模型（路由时确定），因此 virtual_model 归属<b>无歧义</b>；
--     不能用「实例 → 分组」反查代替：一个实例可能同时被多个虚拟模型引用，反查会歧义。
--   * 历史数据留空（null）：老数据落库时无此字段，聚合时归入「未归类」，
--     <b>不丢数据</b>（花费仍按 instance_name 计价）。
--
-- 幂等性：alter table ... add column if not exists / create index if not exists，可重复执行。
-- 注意：H2 默认 DATABASE_TO_UPPER=FALSE，对象名建成小写，与 V1/V2 既有约定一致。
-- ========================================

alter table token_usage add column if not exists virtual_model varchar(255);

-- 虚拟模型维度聚合索引（团队用量按虚拟模型分组统计走这条索引）
create index if not exists idx_token_usage_virtual_model on token_usage (virtual_model);
