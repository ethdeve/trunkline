-- O1 成交价快照（价格固化）：token_usage 落库时固化单价，账单聚合只读快照，
-- 消除「今天调一次单价，上月账单跟着变」的历史漂移（"钱说不清"的根因）。
-- O2 未定价 ≠ ¥0：无价可固的用量标 price_missing，账单侧单列「待定价」。
-- 双方言兼容：H2 2.x 与 PostgreSQL 均支持 ADD COLUMN IF NOT EXISTS。
alter table token_usage add column if not exists price_input_snapshot numeric(18, 6);
alter table token_usage add column if not exists price_output_snapshot numeric(18, 6);
alter table token_usage add column if not exists price_version varchar(64);
alter table token_usage add column if not exists price_missing boolean;

-- 历史回填：迁移前的存量用量没有落库时点价格记录，「首次记录时的价格版本」不可考，
-- 诚实标记 price_missing=true 单列待定价，不按当前价格虚构回填、不混入 ¥0。
-- 快照列保持 NULL（无价可读），新写入的行由应用层固化。
update token_usage set price_missing = true where price_missing is null;
