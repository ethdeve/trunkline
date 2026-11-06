-- ========================================
-- V6: config_data 当前配置与版本快照分离（E1 配置版本收敛）
--
-- 背景（整改方案 E1）：
--   旧模型中 config_data 的 isLatest=true 行具有双重身份——既是「当前生效配置」
--   又是「最新版本快照」。saveConfig 就地覆盖该行会篡改版本快照；applyVersion
--   反向覆盖会让最新版本行内容变成旧配置。
--
-- 新模型（当前值独立行）：
--   - version = 0 的行专职「当前生效配置」（isLatest = true），只由 saveConfig upsert
--   - version >= 1 的行专职「版本快照」（isLatest = false），只追加、永不改写
--   - 应用历史版本 = 追加新版本行 + 重写当前行（指针语义）
--
-- 存量数据迁移（旧行 version >= 1 且 isLatest=true = 双重身份行）：
--   1. 对每个 config_key 的最大版本行，复制出一条 version=0 的当前行
--      （若该 key 已存在 version=0 行则跳过，幂等）
--   2. 将所有 version >= 1 的行降级为 isLatest = false（快照只读）
--   3. 保证每个 (config_key, version) 至多一行，随后加唯一约束守护
--      「版本号来自持久化单调序列」
--
-- 幂等性：全部语句「已满足即空操作」，重复执行安全。
-- ========================================

-- A1：为存量双重身份行补 v0 当前行（每个 key 的 max 版本行 → INSERT 一条 version=0 副本）
insert into config_data (config_key, config_value, version, is_latest, created_at, updated_at)
select m.config_key, m.config_value, 0, true, m.created_at, m.updated_at
from config_data m
where m.version = (
    select max(m2.version) from config_data m2 where m2.config_key = m.config_key
)
  and m.version >= 1
  and not exists (
      select 1 from config_data e where e.config_key = m.config_key and e.version = 0
  );

-- A2：所有版本行（version >= 1）降级为非最新——v0 当前行是唯一的 isLatest=true 行
update config_data set is_latest = false where version >= 1 and is_latest = true;

-- A3：清理迁移前遗留的重复版本行（同 key 同 version 多行时仅保留 id 最大的一条）
delete from config_data c
where c.version >= 1
  and exists (
      select 1 from config_data d
      where d.config_key = c.config_key
        and d.version = c.version
        and d.id > c.id
  );

-- A4：唯一约束——每个 (config_key, version) 至多一行
alter table config_data drop constraint if exists uk_config_data_key_version;
alter table config_data add constraint uk_config_data_key_version unique (config_key, version);

-- A5：查询守护索引（按 key 拉版本列表走这条索引）
create index if not exists idx_config_data_key on config_data (config_key);
