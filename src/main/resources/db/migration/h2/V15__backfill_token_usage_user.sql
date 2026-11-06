-- ========================================
-- V15：回填 token_usage.user_id（v3.5 Trunkline 团队用量出数修复）
-- ========================================
-- 背景（真实缺陷）：
--   「团队用量」按 {@code token_usage} 表聚合，而该表的聚合 SQL 带
--   {@code AND t.userId IS NOT NULL} 过滤条件。
--   但用量落库链路（TokenUsageRecorder.recordTokenUsageNoAuth）此前把 userId
--   **硬编码为 null** —— 所有用量行都被那条 WHERE 排除，团队用量恒为 0。
--   （「调用观测」读的是另一张表 api_call_history，故不受影响，两页数据对不上。）
--
-- 修复分两步：
--   1. 本迁移：回填历史存量行的 user_id；
--   2. 应用代码：落库时带上调用者身份（JWT 用户名 / 密钥持有人），
--      见 TokenUsageRecorder / CallerIdentityResolver。
--
-- 回填来源：{@code api_call_history} —— 该表的 user_id 自 v3.5 起已正确写入
--   （JWT 调用为控制台用户名，API Key 调用为密钥持有人），且与 token_usage
--   共享 trace_id / api_key_id，可安全关联。
--
-- 匹配策略（先精确后模糊，命中即止）：
--   1. trace_id 精确匹配 —— 同一笔调用在两表都有 trace_id，最可靠；
--   2. api_key_id 匹配 —— trace_id 缺失时按密钥回退（同一密钥的持有人相同）。
--
-- 安全性：只 UPDATE user_id 为 NULL 的行，不动已有归属；
--   回填不出来的行保持 NULL（未知调用方），不会凭空造数据。
--
-- 幂等性：WHERE 子句含 {@code user_id IS NULL}，重复执行不会重复覆盖。
--
-- 注意：H2 默认 DATABASE_TO_UPPER=FALSE，对象名建成小写，与既有约定一致。
-- ========================================

-- 回填实现说明：
--   标量子查询用 MIN(h.user_id) 而非 LIMIT 1 —— MIN 保证子查询恰好返回一行
--   （无匹配时返回 NULL），且是标准 SQL：H2 的 MySQL / PostgreSQL 两种模式都支持，
--   不像 LIMIT 在标量子查询里有方言差异。同时 MIN 是确定性的，
--   避免「一个 trace_id 对应多个 user_id 时随机挑一个」的不确定性。

-- 1) 按 trace_id 精确回填
UPDATE token_usage
   SET user_id = (
       SELECT MIN(h.user_id)
         FROM api_call_history h
        WHERE h.trace_id = token_usage.trace_id
          AND h.user_id IS NOT NULL
   )
 WHERE user_id IS NULL
   AND EXISTS (
       SELECT 1
         FROM api_call_history h
        WHERE h.trace_id = token_usage.trace_id
          AND h.user_id IS NOT NULL
   );

-- 2) trace_id 没对上的，按 api_key_id 回填（同一密钥的持有人相同）
UPDATE token_usage
   SET user_id = (
       SELECT MIN(h.user_id)
         FROM api_call_history h
        WHERE h.api_key_id = token_usage.api_key_id
          AND h.user_id IS NOT NULL
   )
 WHERE user_id IS NULL
   AND api_key_id IS NOT NULL
   AND EXISTS (
       SELECT 1
         FROM api_call_history h
        WHERE h.api_key_id = token_usage.api_key_id
          AND h.user_id IS NOT NULL
   );
