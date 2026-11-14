# 变更记录 / Changelog

本项目遵循语义化版本。这里只记录**已经落地**的变化，后续计划见 [README](README-ZH.md)。

## 未发布（在研）— Flyway 单一基线合并（零存量决策）

- 用户确认项目**尚未发布、无生产环境、零存量**，因此不需要任何升级 SQL。
  `db/stock-upgrade/{h2,postgres}/`（旧 V1 + `V4__split_model_price_to_quota.sql`）整体删除，
  专测存量库 V1→V4 的 `FlywayMigrationTest` 一并删除；企业仓 `deploy/README.md` 的
  「存量库升级」章节同步移除。
- `db/migration/{h2,postgres}/` 下原 `V2__cb_metrics_adjustment_applied_index.sql`（#653 过滤索引）
  与 `V3__token_usage_price_snapshot.sql`（四个成交价快照列）全部并入 `V1__baseline.sql`，
  两个增量脚本删除。此后每个方言路径**只有一个 V1**，空库迁移历史只有 `1|SQL`。
  V3 里的 `price_missing` 存量回填不并入：空库无历史行，新行由应用层显式写值，也不设默认值。
- 新增 `SingleBaselineMigrationTest` 钉死「只有 V1 / 无升级包 / 四快照列与索引由 V1 直接建出」；
  `PostgresFlywayMigrationEquivalenceTest` 补同类断言；`H2BaselineMatchesEntityModelTest` 继续
  守卫基线与实体逐项一致。**未发版期间 schema 变更直接改两侧 V1 并同步**，正式发版后才改为
  新增 `V2__…` 增量脚本。

## 未发布（在研）— B4 单价写权限收紧与单价变更审计

- **权限**：`FixedRoleAccessPolicy` 将 `/api/config/prices/**` 的写操作（POST/PUT/PATCH/DELETE）
  从 OPS 收紧到 **ADMIN**（改单价即改钱）；GET 仍为 OPS，接入管理「价格」tab 对运维可见。
  新增 `PriceWriteAccessPolicyTest` 实测四种写方法、深层路径与规则登记顺序，防止首条命中被
  更宽泛的低级别规则抢先覆盖。企业版 `ModelPriceController` 不另加方法级注解，避免两套口径。
- 企业仓同批新增 `enterprise_price_audit`（单价变更审计：操作人、时间、模型、旧价→新价，
  详见企业仓 `deploy/README.md`）。

## 未发布（在研）— 集群事件广播装配修复（B3 多副本前置）

- `RedisClusterEventBus` 有两个构造器且都没有标注注入点，Spring 无法确定用哪一个：
  只要把 `trunkline.cluster.events.enabled=true` 打开就会抛
  `BeanInstantiationException: No default constructor found` 并**阻断整个应用启动**
  （真 PostgreSQL 隔离启动冒烟实测）。现给生产用两参构造器补 `@Autowired`；三参构造器
  仍保留，供测试注入副本标识模拟多副本。
- 新增 `RedisClusterEventBusWiringTest` 走真实容器 `refresh()` 覆盖装配路径 —— 此前
  `ClusterEventBusTest` 全部直接 `new`，从未测到这一层。
- 这是商业版 B3「改价跨副本缓存失效」的前置条件：未修时多副本一开 Redis 广播即启动失败。

## 未发布（在研）— [BREAKING-SPI] v3 真实账本幂等键

- `BillingProvider.BillingEntry` 新增首组件 `traceId`，作为商业版 `enterprise_billing_ledger`
  的幂等键（一次调用一条，重放不重复扣费）；保留五参兼容构造器，存量调用方源码不改也能编译，
  但 **record 组件数量变化属二进制破坏性变更**，`EnterpriseSpiVersion.VERSION` 2→3，企业仓
  `EXPECTED_SPI_VERSION` 必须同步改为 3 并**同批发版**，否则 `spiVersionGate()` 会 fail-fast
  拒绝启动。
- 同批新增 `BillingProvider.precheck(BillingPrecheck)` default 方法（预算前置熔断，社区版恒放行）
  与 `BillingPrecheck` / `PrecheckResult` 两个 record —— 带默认实现的方法与新增类型属兼容扩展，
  不额外推版本。
- `TokenUsageService.toBillingEntry()` 透传 `token_usage.trace_id`；`ServiceRequestHandler`
  在实例选择之前调用 `BillingPrecheckService`，预算耗尽返回 402，异常 fail-open 放行。
- 细则见《Trunkline-计价能力商业化代码改造方案》3.7 / 3.2 #7、#8、#8a。

## 未发布（在研）— [BREAKING-SCHEMA] 计价拆表

- 开源 Flyway 基线 `V1__baseline.sql`（H2 / PostgreSQL）中的 `model_price` 表已改写为
  `model_quota`，只保留额度与限速列；单价列全部移出开源版，改由商业版
  `enterprise_model_price` 表（JDBC 自举）承载。
- **零存量已确认（2026-11-14）**：项目未发布、无生产环境，V1 是唯一基线，可直接改写；
  V1 内容变化会导致 Flyway checksum 变化，但当前不存在任何已执行过 V1 的库。
  存量升级路径（`db/stock-upgrade/` + V4 拆表脚本）按用户决策整体删除，不再提供。
  若首次发版前出现已执行过 V1 的库，须先用
  `SELECT version, checksum, success FROM flyway_schema_history WHERE version = '1'` 判定，
  并重新评估迁移方案，**禁止直接覆盖升级**。
- 判定与迁移细则见《Trunkline-计价能力商业化代码改造方案》P0-2 / 3.4（有存量分支不实施）。

## v0.1.0 — 首个公开基线

### 现在就能用

- 一个入口对接本地模型（Ollama、vLLM、GPUStack、Xinference、LocalAI）和云端模型
- 供应商模板一键接入 13 家常见服务商；其他兼容 OpenAI 调用方式的服务商可用自定义适配器接入
- 除对话外还支持向量、重排序、语音、图片等能力
- 故障自动切换、请求级重试、自动限流、按条件分流
- 账号 + 密钥双凭证、角色权限、敏感信息脱敏、调用留痕
- 用量与额度统计，现成的花费 / 成功率 / 延迟看板
- 控制台说人话分三块，支持亮暗主题与中英双语

### 安全与治理

- 移除内置默认管理员账号，首次启动通过三步向导创建
- 接口 JWT 只接受 `Trunkline_Token` 请求头
- 未登记的接口默认拒绝访问（fail-closed），方法级权限与 URL 权限同口径
- 内置帮助文档仅运维 / 管理员可见，普通用户保留「接入指南」
- 调用记录敏感内容脱敏；初始化状态与数据文件不再硬编码旧路径

### 相对上游 JAiRouter 的破坏性变更

- 默认 H2 数据库由 `jairouter` 改为 `trunkline`
- 移除旧版本配置前缀回落、旧认证头兼容、旧密钥目录复制与 Redis 旧配额前缀迁移
- 遥测服务名、Docker 镜像名与文档品牌统一为 Trunkline，Java 包名等历史内部标识保持不变

### 工程整理

- Flyway 迁移已合并为单一基线，H2 与 PostgreSQL 双方言脚本配套
- 内置帮助文档重写为面向非技术人员的 38 篇中英双语内容
- 清理冗余配置与孤儿脚本，配置结构由守卫测试锁定
