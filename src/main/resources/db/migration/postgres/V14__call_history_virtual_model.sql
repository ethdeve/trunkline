-- ========================================
-- V14：api_call_history 增加虚拟模型（模型分组）维度（Postgres 路径，v3.5 Trunkline UI 改造）
-- ========================================
-- 语义（与 h2/V14__call_history_virtual_model.sql 一致）：
--   * 给 api_call_history 加 virtual_model 列：请求命中的<b>虚拟模型名</b>（模型分组 / 池名，
--     如「公司主力」「便宜日常」），即员工在工具里填的 model 名。
--   * 用途：「调用观测」页要回答「谁在什么时候调了什么」——需要分列展示
--     「员工」（调用者 = 密钥持有人 / JWT 用户）与「虚拟模型」（对外名字），
--     并支持按这两列筛选（设计规格 §5「调用观测」）。
--   * <b>与 model_name 的分工</b>：
--       model_name    = 上游响应体回传的模型名（上游厂商模型名）
--       instance_name = 实际路由到的厂商模型实例
--       virtual_model = 请求命中的虚拟模型（归属维度，员工可见的对外名字）
--     三者语义不同，<b>不可互相替代</b>；model_name 保持原样不动。
--   * <b>为什么不能按「实例 → 分组」反查</b>：同一个厂商模型实例可能同时被多个虚拟模型
--     引用（反查有歧义），而一次请求只命中一个虚拟模型，路由时确定即为唯一答案。
--     本列在<b>调用时</b>由 ServiceRequestHandler 写入请求属性（JAIR_VIRTUAL_MODEL），
--     随请求上下文传到落库点。
--   * 历史数据留空（null）：老数据落库时无此字段，聚合/展示时归入「未归类」，不丢数据。
--
-- 幂等性：alter table ... add column if not exists / create index if not exists，可重复执行。
-- ========================================

alter table api_call_history add column if not exists virtual_model varchar(255);

-- 虚拟模型维度筛选索引（调用观测按虚拟模型过滤走这条索引）
create index if not exists idx_call_history_virtual_model on api_call_history (virtual_model);

-- 员工维度筛选索引（调用观测按员工过滤走这条索引）
create index if not exists idx_call_history_user on api_call_history (user_id);
