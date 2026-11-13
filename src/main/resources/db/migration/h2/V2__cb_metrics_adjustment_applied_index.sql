-- #653 自适应阈值只写日志不落实例（同步项）：为 adjustment_applied 加索引。
-- findPendingAdjustments()（启动补偿与运行时核账）按 adjustment_applied = false 过滤，
-- circuit_breaker_metrics 是按时间窗口持续追加的统计表，无索引时退化为全表扫描。
-- 命名沿用 idx_cb_metrics_* 前缀：PostgreSQL 的 relation 名是 schema 级命名空间，
-- 需避开 circuit_breaker_state_history 已占用的 idx_instance_id 等通用名。
create index if not exists idx_cb_metrics_adjustment_applied
    on circuit_breaker_metrics (adjustment_applied);
