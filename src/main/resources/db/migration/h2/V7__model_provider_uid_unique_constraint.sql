-- ========================================
-- V7：model_provider.provider_uid 唯一性收敛为「具名唯一约束 uk_model_provider_uid」（H2 路径）
-- ========================================
-- 背景与坑（X4 / 2026-10-29 修复）：
--   * V3 用 create unique index 建了 uk_model_provider_uid（唯一索引；在 MODE=MySQL +
--     DATABASE_TO_UPPER=FALSE 下 H2 不把它登记为约束，纯索引）。
--   * 但 H2 路径下 JPA 是 ddl-auto=update：实体一旦对 provider_uid 声明唯一约束，
--     Hibernate 会额外建一个「自动命名约束 UK...」，且其支撑索引名常常复用 uk_model_provider_uid；
--     此时该索引归 UK... 约束所有，H2 禁止直接 DROP INDEX（Err 90085，正是启动报错）。
--   * 因此本脚本不再用 DROP INDEX，而是用动态 SQL 找出 provider_uid 上所有既有
--     UNIQUE / 主键约束（无论名，含自动命名 UK...），逐个 DROP，再统一加具名约束
--     uk_model_provider_uid；同时兼容「V3 普通唯一索引」与「自动命名约束」两种残留状态。
-- 幂等性：动态 DROP（IF EXISTS）+ ADD CONSTRAINT IF NOT EXISTS，可重复执行。
-- ========================================

-- 动态清理：删除该列上所有唯一/主键约束（含自动命名），再删 V3 普通唯一索引
CREATE ALIAS IF NOT EXISTS DROP_OLD_PROVIDER_UID_CONSTRAINT AS $$
void execute(java.sql.Connection conn) throws Exception {
    java.sql.Statement st = conn.createStatement();
    java.sql.ResultSet rs = st.executeQuery(
        "SELECT tc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc "
      + "JOIN INFORMATION_SCHEMA.CONSTRAINT_COLUMN_USAGE ccu "
      + "  ON tc.CONSTRAINT_NAME = ccu.CONSTRAINT_NAME AND tc.TABLE_NAME = ccu.TABLE_NAME "
      + "WHERE tc.TABLE_NAME = 'model_provider' "
      + "  AND tc.CONSTRAINT_TYPE IN ('UNIQUE','PRIMARY KEY') "
      + "  AND ccu.COLUMN_NAME = 'provider_uid'");
    String dq = new String(new char[]{ '"' });
    while (rs.next()) {
        String cn = rs.getString(1);
        java.sql.Statement dst = conn.createStatement();
        dst.execute("ALTER TABLE model_provider DROP CONSTRAINT IF EXISTS " + dq + cn + dq);
        dst.close();
    }
    rs.close();
    st.close();
    try { java.sql.Statement s2 = conn.createStatement(); s2.execute("DROP INDEX IF EXISTS uk_model_provider_uid"); s2.close(); } catch (Exception ignore) {}
}
$$;

CALL DROP_OLD_PROVIDER_UID_CONSTRAINT();
DROP ALIAS IF EXISTS DROP_OLD_PROVIDER_UID_CONSTRAINT;

ALTER TABLE model_provider ADD CONSTRAINT IF NOT EXISTS uk_model_provider_uid UNIQUE (provider_uid);
