# 变更记录 / Changelog

本项目遵循语义化版本。这里只记录**已经落地**的变化，后续计划见 [README](README-ZH.md)。

## 未发布（在研）— 多租户隔离声明体系与多实例共享态修复

- **租户隔离从「隐式」变「显式 + 启动期 fail-fast」**：`@TenantShared` 注解语义从「文档用途」升级为
  「**有意全局共享、不做行级隔离**」的声明，必须携带 `reason`；与之相对，业务数据实体显式挂
  `@Filter(name=tenantFilter)` 表达行级隔离。新增 `TenantIsolationStartupGuard`：
  在 `filter-enabled=true` 且 `tenantFilter` 激活时，反射扫描 `persistence.jpa.entity` 包下全部
  `@Entity`，任何**既未挂 `@Filter` 又未标 `@TenantShared`** 的实体直接拒绝启动。
  配套的 `TenantIsolationAuditTest` 升级为全实体覆盖断言（`isolated()` 阈值下调至 ≥5）。
- **身份 / 上游基础设施平面统一全局共享（两个平面原则）**：依「身份·上游基础设施·运维审计平面 = 全局
  `@TenantShared`；租户业务数据/计量平面 = `@Filter`」的划分，将 5 个实体由 per-tenant 重分类为全局共享，
  删除其 `tenant_id` 列与租户索引（两侧 `V1__baseline.sql` 同步）：
  - `JwtAccountEntity`：认证与 RBAC 身份存储（JWT 登录账号 + API Key `holder` 用户目录）；全局化消除
    「操作员登录加载账号依赖 tenant 上下文」的引导脆弱性，与无 `tenant_id` 的 `ApiKey` 凭证平面一致；
    删除 `jwt_accounts.tenant_id` 与 `idx_jwt_accounts_tenant`。
- **租户隔离实体原生 SQL 守卫（P0 第 3 点收敛）**：`@Filter` 不作用于 native SQL / JDBC，故新增
  `TenantNativeQueryGuardTest`——CI 静态扫描全部 Spring Data 仓储，一旦租户隔离实体（`@Filter`）上出现
  `nativeQuery=true` 方法、或 native SQL 直接点名五张租户表（`config_data`/`service_config`/
  `quota_ledger`/`token_usage`/`api_call_history`）即失败。审计结论：开源主代码对租户表查询均为 JPQL、
  `nativeQuery=true` 命中数为 0，盲区不在当前代码；该守卫将「将来误加 native 查询」钉死在提交前。
  据此判定「tenant-aware 仓储基类」在零 native 查询 + 私有化单租户下为 YAGNI，不再新建。
  - `ModelProviderEntity` / `ServiceInstanceEntity`：厂商接入与上游服务注册表（含供应商 `Authorization`
    等凭据），属平台拓扑而非租户业务数据，与已全局的 `ModelQuotaEntity` 同平面；删除两表 `tenant_id`
    与 `idx_model_provider_tenant` / `idx_service_instance_tenant`；`service_instance` 唯一键仍为
    `(provider_id, instance_name)` 不含租户。
  - `SecurityBlacklistEntity` / `SecurityAuditEventEntity`：安全黑名单与安全审计事件，属「一处封禁 /
    一处审计全员生效」的运维审计证据，跨租户共享；删除两表 `tenant_id` 与 `idx_security_blacklist_tenant`
    / `idx_audit_tenant`；`security_blacklist` 唯一键仍为 `(blacklist_type, target_value)`。
- **其余缺口实体经核对为全局共享**（厂商额度、熔断配置、运维审计/遥测、配置审计流水等），逐一标注
  `@TenantShared(reason=...)` 而非机械加列——私有化部署下机械加列会破坏全局唯一键与共享语义。
  最终仅 **5 个** `@Filter` 业务实体保留行级隔离：`ConfigEntity`、`ServiceConfigEntity`、
  `TokenUsageEntity`、`QuotaLedgerEntity`、`ApiCallHistoryEntity`。
- **修 `detectMultiReplica` 漏配静默**：不改 `TRUNKLINE_REPLICAS` 默认值（`:1`）与
  `application-prod.yml:48-51` 明确「刻意不设 0」的设计。新增静态判定
  `detectReplicaDeclarationMismatch(1, k8sEnv)`：显式 `replicas=1` 但 K8s 环境存在（HPA 动态扩容）
  的组合，由 `MultiReplicaSharedStateChecker` 输出**明确 WARN** 而非静默放行。
- **修 `LicenseStateStore` 多副本共享态**（企业仓）：路径改为可配置 `trunkline.enterprise.license-state-path`
  （默认 `~/.trunkline/license.state`），写入加 JVM 锁 + 文件锁 + 原子移动；多副本未指向共享卷时启动 WARN；
  状态存储故障 fail-safe，不影响 license 主校验的 fail-closed。详见企业仓 `deploy/README.md`。
- **口径收敛**：本仓库**不承诺多租户发布级隔离**；私有化部署（功能位全局 license 级、不做 SaaS 灰度）
  下上述改动消除「假隔离」与凭据泄露面，属于防御性加固而非多租户商业化能力。

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
