-- ========================================
-- V12：model_price.model_name 唯一性收敛为「具名唯一约束 uk_model_price_model_name」（H2 路径）
-- ========================================
-- 背景（与 V7 同源，X4 遗留）：
--   * V9 用 create unique index 建了 uk_model_price_model_name（唯一索引；在 MODE=MySQL +
--     DATABASE_TO_UPPER=FALSE 下 H2 只把它登记为索引，不登记进 TABLE_CONSTRAINTS）。
--   * 但实体侧（ModelPriceEntity）声明的是具名<表级唯一约束> uk_model_price_model_name，
--     Hibernate 建表时登记为 UNIQUE 约束。
--   * 两侧形态不一致 → H2BaselineMatchesEntityModelTest 的 constraints 节假红
--     （Flyway 侧缺 model_price.uk_model_price_model_name UNIQUE）。
--   * 本脚本先把该列上的所有唯一/主键约束（含 Hibernate 自动命名的 UK...）逐个 DROP，
--     再删 V9 的普通唯一索引，最后统一加具名约束 uk_model_price_model_name。
--
-- 幂等性：动态 DROP（IF EXISTS）+ ADD CONSTRAINT IF NOT EXISTS，可重复执行。
-- 注意：H2 默认 DATABASE_TO_UPPER=FALSE，对象名建成小写。
-- ========================================

CREATE ALIAS IF NOT EXISTS DROP_OLD_MODEL_PRICE_NAME_CONSTRAINT AS $$
void execute(java.sql.Connection conn) throws Exception {
    java.sql.Statement st = conn.createStatement();
    java.sql.ResultSet rs = st.executeQuery(
        "SELECT tc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc "
      + "JOIN INFORMATION_SCHEMA.CONSTRAINT_COLUMN_USAGE ccu "
      + "  ON tc.CONSTRAINT_NAME = ccu.CONSTRAINT_NAME AND tc.TABLE_NAME = ccu.TABLE_NAME "
      + "WHERE tc.TABLE_NAME = 'model_price' "
      + "  AND tc.CONSTRAINT_TYPE IN ('UNIQUE','PRIMARY KEY') "
      + "  AND ccu.COLUMN_NAME = 'model_name'");
    String dq = new String(new char[]{ '"' });
    while (rs.next()) {
        String cn = rs.getString(1);
        java.sql.Statement dst = conn.createStatement();
        dst.execute("ALTER TABLE model_price DROP CONSTRAINT IF EXISTS " + dq + cn + dq);
        dst.close();
    }
    rs.close();
    st.close();
    try { java.sql.Statement s2 = conn.createStatement(); s2.execute("DROP INDEX IF EXISTS uk_model_price_model_name"); s2.close(); } catch (Exception ignore) {}
}
$$;

CALL DROP_OLD_MODEL_PRICE_NAME_CONSTRAINT();
DROP ALIAS IF EXISTS DROP_OLD_MODEL_PRICE_NAME_CONSTRAINT;

ALTER TABLE model_price ADD CONSTRAINT IF NOT EXISTS uk_model_price_model_name UNIQUE (model_name);
