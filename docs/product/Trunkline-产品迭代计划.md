# Trunkline 产品迭代计划

> **重制说明（2026-11-03）**：本文件按最新产品决策全面重制——社区版优先、版本号冻结 0.1.0、告警外推归商业版、Open-Core 边界以 `editions.md` 为权威。原「基于代码实况」的探查底账保留为附录 A。
> 探查基线：2026-10-28，源码实况核对（非 README 推断）；最后更新：2026-11-03。
>
> **权威文件互引（任一变更需同步其他两份）**：
> - 版本边界（对客户/法务）：`docs/product/editions.md`
> - 社区版专项规划（指标/短板/验收）：`docs/product/社区版完善规划.md`
> - 版本节奏与任务号（对研发）：本文件
>
> **版本号冻结决策（2026-11-03）**：产品版本号固定 **0.1.0**（`pom.xml` 与 `frontend/package.json` 已统一），等社区版验收清单（§八）全过后再一次性升版，升版时同步完成品牌统一动作（Docker 镜像 tag、CHANGELOG 首版）。下文 S1~S4 为**阶段名**，对应原 v0.2.0~v0.5.0，不代表发布版本号。
>
> **告警外推归属决策（2026-11-03）**：告警 Webhook 推送**归商业版**（任务 74，原社区版任务 57 移入），社区版不做——社区版承诺收窄为「控制台内故障可见」。

---

## 一、北极星指标（社区版只对这三件事负责）

| 指标 | 定义 | 目标 | 为什么 |
|---|---|---|---|
| **TTV**（Time-to-Value） | `docker run` → 接入首个厂商 → 发出第一把密钥 → 员工发出第一句对话的总时长 | ≤ 10 分钟 | 漏斗第一环的转化率代理指标；每多一步，自建用户流失一截 |
| **员工激活率** | 部署后 7 天内实际发起过调用的员工数 / 总账号数 | ≥ 60% | 续费由「员工真的在用」决定，员工不用 = 部署变摆设 |
| **值班自足率** | 运维不经作者介入、仅凭控制台即可发现并恢复故障的比例 | ≥ 90% | 告警主动推送已归商业版；社区版承诺「打开控制台就能看清故障」 |

**社区版不背的指标**：付费转化（ENT 版与销售的事）、多租户、SSO、告警外推（ENT 专属，见 `editions.md`）。

## 二、阶段总览

| 阶段 | 周期 | 主题 | 线 |
|---|---|---|---|
| **S1**（原 v0.2.0） | 2 周 | 品牌清理 + 开源发布 | OS |
| **S2**（原 v0.3.0） | 3 周 +1 天 | 代码清债 + 前端收编 + OS/ENT 骨架预埋 | OS |
| **S3**（原 v0.4.0） | 3 周 | 生产稳定性 + 安全审计 | OS |
| **S4**（原 v0.5.0） | 持续 | 配置减负 + 员工激活 | OS |
| **ENT-MVP**（v1.0.0-ENT） | 10-12 周（与 S2 起并行，含任务 72/73/74） | 商业版 MVP | ENT |

## 三、已完成项快照（2026-11-03）

| 项 | 产出 |
|---|---|
| ✅ 视觉层对齐设计规格 | `frontend/src/styles/tokens.css` 等全量对齐规格色板（青绿主色/中文字体栈/圆角体系），`npm run build` 通过，零功能影响 |
| ✅ 规格文档与代码菜单反向对齐 | `docs/Trunkline-UI设计规格.md` §2/§3/§5/§7 已与 `menu.ts` 实况一致；菜单以代码为准，策略中心保持 1 tab |
| ✅ Open-Core 边界对照页 | `docs/product/editions.md`（社区版 ✅ vs 商业版 🧭，含法务采购 FAQ） |
| ✅ 社区版专项规划 | `docs/product/社区版完善规划.md`（三指标/五短板/阶段补强/验收清单） |
| ✅ 版本号统一 0.1.0 | `pom.xml`（原 3.3.1）与 `frontend/package.json` 一致 |
| ✅ TTV 自检脚本 | `scripts/ttv-check.sh`：健康检查→登录→建密钥→首句对话分步计时，三态退出码，可直通 CI 冒烟 |
| ✅ 测试基线修复（S1 任务 2 / S2 任务 26 提前） | 修复 6 个 P0-L 接入后的存量测试欠账；`mvnw verify` 4313 测试全绿 |
| ✅ Docker 镜像前缀（S1 任务 4） | `pom.xml` `docker.image.prefix=trunkline` |
| ✅ 配置前缀品牌清理（S1 任务 2/5） | `jairouter.` → `trunkline.` 全量替换（src 配置树根/Java `@ConfigurationProperties`/`@Value`/指标名/监控栈 yml/compose/K8s/前端文案），Docker Hub 命名空间 `sodlinken/jairouter` → `trunkline/trunkline`，JWT issuer、OTel serviceName、micrometer 前缀、`application` 标签同步；`application.yml` 头注释改 Trunkline |
| ✅ 旧前缀兼容映射器（S1 任务 3） | `JairouterLegacyPropertyMapper`（EnvironmentPostProcessor）：存量 `jairouter.*` 配置与环境变量自动映射为 `trunkline.*`，新前缀显式配置优先，日志就绪后打弃用 WARN；含 5 个单元测试 |
| ✅ 配额 Redis key 迁移（S1 任务 6） | 前缀默认值 `jairouter:quota` → `trunkline:quota`；新增 `QuotaRedisKeyMigrator`（启动期一次性把旧前缀存量计数**求和并入**新前缀后删除旧 key——比原计划的"双写"更简单且集群安全，滚动升级残余窗口以分钟计且旧 key 按 TTL 自清）；其余瞬态 Redis key（state/sched:lock/ratelimit/events/security 缓存）直接改名；含 5 个迁移单元测试 |
| ✅ 主配置 key 兼容改名（S1 任务 7） | `model-router-config` → `trunkline-config`：新增 `ConfigKeyCompatStoreManager` 装饰器（读回落旧 key / 写落新 key / 删除时连带清理防复活），在 `StoreManagerConfiguration` 唯一装配点生效；服务层字面量统一收敛为常量 |
| ✅ README 重写（S1 任务 9） | `README.md` / `README-ZH.md`：Trunkline 口吻、开头声明基于 JAiRouter Apache 2.0 二开、新增「从旧版升级」兼容层说明、真实发展路线（0.1.0 冻结 + S1-S4 阶段 + 商业版指针）、徽章与链接指向 `trunkline.com` / `Lincoln-cn/trunkline` |
| ✅ 文档与脚本品牌替换（S1 任务 10） | `docs/`（CNAME/robots/zh/en）、`scripts/build`（sh+ps1）、`Dockerfile*`（容器用户名）、`.env.example`（JWT_ISSUER 等同步新默认值）、`.cspell.json` 加 trunkline 词条 |
| ✅ 发布配套文件（S1 任务 11-16） | `NOTICE`（Apache 2.0 归属声明）、`CONTRIBUTING.md`（含 jairouter.* 禁用规则与归属规范）、`CODE_OF_CONDUCT.md`、Issue/PR 模板、`.github/workflows/build.yml`（构建+测试+Docker 冒烟）、`docker-publish.yml`（tag 触发多架构推送 `trunkline/trunkline`）——内容均按 Trunkline 口吻撰写，`jairouter` 仅保留在归属/兼容性正当引用处 |
| ✅ CHANGELOG 修正（S1 任务 17） | `CHANGELOG.md` [Unreleased] 补记 S1 品牌切换与三层兼容层；修正 0.1.0-rc1 Notes 中「namespace 仍是 jairouter.*、v0.2.0 才改名」的过时表述（实际 S1 已改完且版本冻结 0.1.0） |
| ✅ @modified 归属标注（S1 任务 8） | 129 个 Trunkline 修改过且有 `@author` Javadoc 头的 Java 文件补 `@modified Trunkline Team` 标注（198 个无文件级注释的文件跳过） |

---

## 四、阶段 S1：品牌清理与开源发布（2 周）

> 目标：让仓库能对外公开，clone 后 `mvn package && docker run` 能跑，品牌统一为 Trunkline。

### 4.1 品牌与合规清理

| # | 任务 | 文件/位置 | 预估 |
|---|---|---|---|
| 1 | 新建 `NOTICE` 文件 | 根目录 | 0.5h |
| 2 | 批量替换配置前缀 `jairouter.` → `trunkline.` | `src/main/resources/config/**/*.yml` + Java `@Value`/`@ConfigurationProperties` | 8h |
| 3 | 加 legacy 兼容映射：`JairouterLegacyPropertyMapper`，旧前缀自动重映射并打印 deprecation 警告 | 新建 `common/config/` | 4h |
| 4 | `pom.xml` 改 `docker.image.prefix=trunkline`，`artifactId` 从 `model-router` 改 `trunkline`（保留旧 artifactId 兼容期）【✅ prefix 已改；artifactId 改名牵涉构建脚本与文档路径，**延后至升版时与镜像 tag 一并处理**】 | pom.xml | 1h |
| 5 | `application.yml` 头注释从"JAiRouter 主配置文件"改为"Trunkline 主配置文件" | application.yml | 0.5h |
| 6 | Redis key 前缀 `jairouter:quota:` → `trunkline:quota:`，做双写平滑切换【✅ 改为**启动期一次性迁移**（`QuotaRedisKeyMigrator` 求和并入 + 删旧 key），比双写简单且集群安全；热路径零开销；已落地】 | RedisCounterBackend | 4h |
| 7 | configKey `model-router-config` → `trunkline-config`，兼容旧 key【✅ `ConfigKeyCompatStoreManager` 装饰器读回落，已落地】 | ConfigService | 2h |
| 8 | Java 文件作者注释：保留 `@author JAiRouter Team`，Trunkline 修改过的加 `@modified Trunkline Team` | 批量处理 | 2h |
| 9 | README.md / README-ZH.md 重写为 Trunkline 口吻，开头声明基于 JAiRouter Apache 2.0 二开【✅ 已落地，另含升级兼容层说明与真实发展路线】 | 根目录 | 4h |
| 10 | 文档里 `jairouter.com` 链接、`sodlinken/jairouter` Docker 镜像引用全部替换【✅ docs/CNAME/robots/scripts/Dockerfile/.env.example 已落地；`trunkline.com` 域名需注册后配置 DNS+CNAME】 | docs/ + 配置注释 | 3h |

### 4.2 工程化与发布

| # | 任务 | 预估 |
|---|---|---|
| 11 | 新建 `.env.example`，列全必需环境变量（JWT_SECRET、INITIAL_ADMIN_PASSWORD、REDIS_PASSWORD、DB URL 等） | 2h |
| 12 | 新建 `CONTRIBUTING.md`（环境搭建、构建命令、代码规范、PR 流程） | 3h |
| 13 | 新建 `CODE_OF_CONDUCT.md`（Contributor Covenant） | 0.5h |
| 14 | 新建 `.github/ISSUE_TEMPLATE/bug_report.md` + `feature_request.md` + `.github/PULL_REQUEST_TEMPLATE.md` | 2h |
| 15 | 新建 `.github/workflows/build.yml`：push/PR 跑 `mvn verify` + `cd frontend && npm ci && npm run build` | 4h |
| 16 | 新建 `.github/workflows/docker-publish.yml`：打 tag 自动 build 并推 `trunkline/trunkline:${tag}` | 3h |
| 17 | 新建 `CHANGELOG.md`（**延后到升版时首版**，本阶段只建空文件占位） | 0.5h |
| 18 | 本地干净环境全流程验证：新 clone → `mvn package` → `docker build` → `docker run` → 默认账号登录控制台，随后跑 `scripts/ttv-check.sh` 记录 TTV 基线 | 3h |
| 19 | GitHub 公开仓库创建 + 推送（**不打 release 版本号**，版本冻结期间用 commit tag） | 2h |

### 4.3 S1 不做的事

- 不做任何新功能；不动业务逻辑
- 不删旧控制台（保留 8080/admin 并行）
- 不引入 OS/ENT 模块拆分（S2 再做）
- 不发版本号（冻结 0.1.0）

---

## 五、阶段 S2：代码清债与前端收编（3 周 +1 天）

> 目标：把代码里的 stub/未实现/零消费方清理干净，把新 Vue 控制台的 ComingSoon 占位页收编完，新旧控制台合并。
>
> **进度（2026-11-04）**：任务 20-25 已全部处置完毕；26/26a 此前完成；27-30 与 27a 经核实大部分已提前落地（无 ComingSoon 路由残留）。

### 5.0 代码清债处置记录（2026-11-04）

| # | 处置 | 说明 |
|---|---|---|
| 20 | ✅ 诚实化 | `MonitoringController` custom-tags 端点从「假成功」改为 501 NOT_IMPLEMENTED（无控制台消费方、运行时动态改指标 tag 无实现基础）；要恢复应先实现动态更新 |
| 21 | ✅ 死代码移除 | `DataSyncService` 接口删除 6 个零调用方方法（syncFromRedisToStoreManager/performBidirectionalSync/checkDataConsistency/repairDataInconsistency/getSyncStats/cleanupSyncData）+ ConsistencyCheckResult 内部类；实现类 524→322 行，删除全部「返回 success=true 但啥都没做」的假成功 stub；保留唯一真实路径 recoverFromStoreManagerToRedis + performStartupRecovery（启动恢复在用） |
| 22 | ✅ 此前已完成 | issue #224 批 B 已删除零消费方字段，头注释有完整消费方核对记录 |
| 23 | ✅ 核实无需改 | `ServiceRequestHandler:642` 与 `CapabilityChecker:45` 的 NOT_IMPLEMENTED 是正确的诚实报错（适配器不支持该能力 → 501 + 明确文案），非 stub |
| 24 | ✅ 核实为零 | 主代码 TODO/FIXME 扫描 0 处 |
| 25 | ✅ 明确用途 | `application-legacy.yml` 头注释更新为 Trunkline 口吻：明确只服务「无认证老部署升级过渡」，新部署禁用，S3 评估移除并预留 issue 反馈通道 |

### 5.0b OS/ENT 骨架与数据层预埋处置记录（2026-11-04，任务 32-42）

| # | 处置 | 说明 |
|---|---|---|
| 32 | ✅ 扩展点接口 | `enterprise.spi` 包新增 5 个扩展点：`TenantContext`/`ModerationProvider`/`BillingProvider`（计划名 BillingService，为避免与既有账单聚合服务同名混淆改现名）/`SsoConnector`/`LicenseVerifier`，DTO 用 record，全部零依赖 |
| 33 | ✅ Noop 实现 | `enterprise.noop` 包：`SingleTenantContext`（恒 default）/`NoopModerationProvider`（放行）/`NoopBillingProvider`（回执）/`NoopSsoConnector`（不支持）/`NoopLicenseVerifier`（community 恒合法） |
| 34 | ✅ AutoConfiguration | `TrunklineEnterpriseAutoConfiguration` + `META-INF/spring/...AutoConfiguration.imports`：全部 `@ConditionalOnMissingBean` 兜底，商业 jar 用 `@AutoConfiguration(before=...)` 即可覆盖；另有 `TenantContextHolder` 静态入口（ContextRefreshed 后绑定实际实现）。ApplicationContextRunner 测试 4 项全绿 |
| 35 | ✅ V16 迁移 | h2/postgres 双侧 `V16__add_tenant_id_columns.sql`：7 张业务表（jwt_accounts/token_usage/api_call_history/security_audit/model_provider/config_data/service_config）加 `tenant_id VARCHAR(64) NOT NULL DEFAULT 'default'` + 同名索引。注意：quota_ledger 不在批内——其 tenant_id 已在 V1 基线但语义是「配额维度」（空串哨兵），与租户归属不同；计划原文的「V6」编号已过时（Flyway 已到 V15）。实体 `@Table` 同步加 `@Index`（H2BaselineMatchesEntityModelTest 门控），legacy 路径构造同步剥列（H2FlywayMigrationEquivalenceTest / PG 探针） |
| 36 | ✅ 过滤器预埋（默认关） | Hibernate `@FilterDef`（entity 包级 package-info）+ 7 实体 `@Filter(name="tenantFilter")` + `TenantJpaFilter`（AOP，`@ConditionalOnProperty trunkline.tenant.filter-enabled=true` 才装配）。**社区版默认关：任何查询行为零变化**；严格 fail-closed 语义由 enterprise jar 替换切面实现 |
| 37 | ✅ 零迁移预埋 | `TenantKeyBuilder`：约定 `trunkline:{domain}:` ≡ `trunkline:default:{domain}:`（default 租户不带段）——社区版 key 完全不变、升商业版无需 Redis 迁移，多租户自动分段；单测 3 项。计划原文「改前缀+双读过渡」方案降级为此（避免二次全命名空间迁移风险） |
| 39 | ✅ 角色常量预留 | `TrunklineRoles`（PLATFORM_ADMIN/TENANT_ADMIN）+ `JwtPrincipal.isPlatformAdmin()/isTenantAdmin()`；社区版不签发、授予逻辑随 ENT-MVP（避免现在动授权流） |
| 40 | ✅ claim 便捷读取 | `JwtPrincipal.getTenantId()`：老 token 无 tenantId claim 缺省 default，不强制重新登录；签发侧不动 |
| 41 | ✅ 字段约定 | `MenuItem.feature?: string`（billing/sso/moderation/multi-tenant/audit-proof/alerting）+ 注释约定锁态渲染规则随 ENT-MVP 落地；社区版现无 ENT 专属菜单，不预置锁死菜单（避免对用户展示永远灰态的负资产） |
| 42 | ✅ 切换器占位 | `ConsoleLayout` 顶栏新增「默认租户 + 商业版徽章」禁用态胶囊（tooltip 说明升级通道），样式走 --ja-* 令牌 |

### 5.1 代码清债

| # | 任务 | 位置 | 预估 |
|---|---|---|---|
| 20 | `MonitoringController.java:221` 自定义标签更新——要么实现，要么删掉这个 endpoint 返回 410 | monitor/controller | 2h |
| 21 | `DataSyncServiceImpl.java:438` "Sync to StoreManager not implemented yet"——要么补实现，要么移除这段死代码 | auth/security/service/impl | 4h |
| 22 | `TracingSecurityConfig.java:27` 提到的"全仓零消费方"字段（audit-access-attempts 等）——清理掉，不要留配置项但没人读 | monitor/tracing/config | 3h |
| 23 | `ServiceRequestHandler.java:606` 和 `CapabilityChecker.java:45` 的 NOT_IMPLEMENTED 路径——梳理哪些能力真的没实现，补能力或明确报错 | router | 4h |
| 24 | 全局扫 `// TODO`/`// FIXME`，能修的修，不能修的建 issue 跟踪 | 全仓 | 4h |
| 25 | 清理 `application-legacy.yml`——明确它是干嘛的，哪些用户还需要 | resources | 2h |
| 26 | ✅ 测试全绿 | **2026-11-03 已完成**：修复 6 个 P0-L 存量欠账 + 品牌替换回归；2026-11-04 复验 4341 测试全绿 |  |
| 26a | ✅ 兼容标识平滑切换（2026-11-04） | ① 认证头：默认值 `Jairouter_Token` → `Trunkline_Token`（JwtConfig / config/auth/jwt.yml），服务端双读回落旧头（DefaultAuthenticationConverter，配置头优先），前端 5 处改发新头，WsTokenTest 补旧头回落用例（10/10 过）；② Redis key：S1 已完成（QuotaRedisKeyMigrator，零残留复验）；③ 密钥目录 `~/.jairouter/` → `~/.trunkline/`：首次解析时**复制**旧密钥到新目录（不删旧文件，回滚可用——密钥丢失则历史记录无法解密，故复制而非移动）。另清掉 docs/zh+docs/en 站点 160 个文件的旧品牌残留（S1 任务 10 漏网；changelog 历史条目按规范保留） | security/persistence | 1 天 |
| 26a | 【品牌清理发现的兼容标识，需平滑切换】① 认证头默认值 `Jairouter_Token`（前端 3 处 + 后端 filter，改默认破坏 API 契约，需双读过渡）② Redis 冒号 key `jairouter:quota:` 等（`QuotaProperties`/`CacheConfig`/`RedisClusterEventBus` 等 6 处默认值，需双写切换，即任务 6）③ 密钥目录 `~/.jairouter/call-history.key`（改路径丢密钥，需迁移逻辑） | security/persistence | 1 天 |

### 5.2 前端控制台收编

| # | 任务 | 位置 | 预估 |
|---|---|---|---|
| 27 | ✅ 已随 v3.5 改造完成 | `_attic/ComingSoon.vue` 菜单 map 对应的页面已在 v3.5 四组菜单改造中逐个建成（my/team/strategy/system 等真实视图），无遗留待收编项（2026-11-04 复核） | frontend/views | 12h |
| 28 | ✅ 前提已消解 | `/admin` 不是旧控制台——pom 的 copy-frontend-build 直接把 `frontend/dist` 拷到 `static/admin`，`/admin` 就是**当前唯一** Vue 控制台（v3.5 旧皮肤已删），无需删除或加横幅（2026-11-04 核实） | router/controller | 4h |
| 29 | ✅ 已实现（P0-B） | `router.beforeEach`：未登录跳登录；权限不足跳 403 页并携带被拦路径与缺失权限码清单（可解释、不静默）；`utils/auditPermissionConsistency.ts` 还有权限码拼写静态校验 | frontend/router | 3h |
| 30 | ✅ 已实现 | `utils/request.ts` 拦截器 + `utils/friendlyError.ts`（401/403/网络错人话映射，S2 员工激活包又补了登录页同款）；可观测性待 ENT-MVP 的 alerting 增强 | frontend/utils | 3h |

### 5.3 孤儿页面归档（社区版规划追加，+1 天）

| # | 任务 | 预估 |
|---|---|---|
| 27a | ✅ 已归档（2026-11-04） | 精确扫描（全路径 + 相对路径 import 双匹配）确认 **9 个真孤儿 4492 行**：callHistory/TokenUsageStatistics、circuit-breaker/{History,Monitoring}、exception/{ExceptionManagement,ExceptionStatistics}、monitoring/{QuotaUsageMonitoring,SlowQueryAnalysis}、rate-limiter/Monitoring、placeholder/ComingSoonView——均为 v3.5 调用观测拆解（ObserveView 头注释有去向记录）替代的旧 tab 页；已移入 `views/_attic/27a-orphaned/`，build 通过（3039 模块数不变）。计划所列其余「旧页」实为被引用的活组件（rules dialogs、adapter 组件等），不在归档之列 | 1 天 |

### 5.4 OS/ENT 代码隔离骨架（为商业版铺路）

| # | 任务 | 预估 |
|---|---|---|
| 31 | 拆 Maven module：`trunkline-core`、`trunkline-common`、`trunkline-web`（先不拆 frontend，单 module 也行） | 8h |
| 32 | 在 common 定义扩展点接口：`ModerationProvider`、`TenantContext`、`BillingService`、`SsoConnector`、`LicenseVerifier` | 4h |
| 33 | 在 core 提供 Noop 默认实现：`NoopModerationProvider`（放行）、`SingleTenantContext`（永远返回 `'default'`）、`NoopBillingService`、`NoopSsoConnector` | 4h |
| 34 | Spring Boot AutoConfiguration：classpath 有 `trunkline-enterprise.jar` 且 license 校验通过就覆盖默认实现，否则走 Noop | 4h |

### 5.5 社区版→商业版原地升级预埋（数据层必须现在做，不能等 ENT-MVP）

> 原则：**社区版 = 商业版在 `maxTenants=1` + 全部企业 feature flag=false 时的一个特例。** 同一份二进制，靠 license 文件解锁，不换包、不重部署、不做数据搬迁。

| # | 任务 | 位置 | 预估 |
|---|---|---|---|
| 35 | 所有业务表 Flyway 加 V6：`ALTER TABLE xxx ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default'`（apikey、quota_ledger、call_record、audit_event、provider、config、model_pool 等全部） | db/migration | 4h |
| 36 | 写 `TenantJpaFilter`（Hibernate Filter 或 MyBatis Plus TenantLineHandler）：所有 Repository 自动拼 `WHERE tenant_id = ?`；社区版 `TenantContextHolder` 永远返回 `'default'` | common/tenant | 6h |
| 37 | Redis key 前缀从 `trunkline:{domain}:` 改成 `trunkline:{tenantId}:{domain}:`，社区版 = `trunkline:default:{domain}:`；老 key 做双读过渡 | RedisCounterBackend / KeyBuilder | 6h |
| 38 | provider 表加 `tenant_id`：平台级上游写 `'system'`，租户私有上游写租户 ID；社区版用户配的上游自动归 `'system'` | provider 实体 | 2h |
| 39 | 角色枚举预留 `ROLE_PLATFORM_ADMIN`（超管）和 `ROLE_TENANT_ADMIN`（租户管理员）；社区版 admin 同时挂两个角色 | security | 2h |
| 40 | JWT claim 加 `tenantId` 字段，老 token 兼容（缺省 = `'default'`），不强制所有人重新登录 | auth/jwt | 2h |
| 41 | `menu.ts` 每个菜单项加 `feature` 字段（`billing`/`sso`/`moderation`/`multi-tenant`/`audit-proof`/`alerting`）；未解锁时渲染灰态 + 锁图标 + "商业版"徽章，点击弹升级说明抽屉 | frontend/config | 3h |
| 42 | 顶栏预留租户切换器位置：社区版只显示一个"默认租户"（禁用下拉），商业版激活后出现切换器 | ConsoleLayout.vue | 2h |
| 43 | License 文件格式：RSA 签名 + 机器指纹（内网离线校验），字段含 `customerName`、`maxTenants`、`expireDate`、`featureFlags`；不联网、不调用外网激活接口 | common/license | 6h |
| 44 | 升级向导（商业版首次启动检测到无 license 时不出现）：只让超管设置平台名称 + 默认超管邮箱，其他全自动；**不问"老数据归到哪个租户"** | 启动器 | 3h |

> **升级后老数据归属映射（写死，不让用户选）：**
> - 老 `admin` → 平台超管 + default 租户管理员（一人两职）
> - 老 API Key、配额账本、调用记录、审计日志 → 全部 `tenant_id='default'`
> - 老用户自己配的上游 Provider → `tenant_id='system'`（平台共享池）

### 5.6 社区版权权固定化（2026-11-04 用户决策，替代原 RBAC 体系）

> **决策**：社区版不做可配置 RBAC。权限 = 「角色 × 菜单分组」固定矩阵：
> **员工（USER）=「我的」分组；运维（OPERATOR）=「我的」+「团队」「策略」分组；管理员（ADMIN）=全部。**
> 可配置 RBAC（权限码/数据驱动映射）归商业版（任务 72）。

| 项 | 处置 |
|---|---|
| 新授权内核 | `auth.security.access` 包：`FixedRoleAccessPolicy`（SELF/OPS/ADMIN 三档，40+ 条 URL 规则代码内置、首条命中优先）+ `FixedRoleAuthorizationManager`（按 `ROLE_*` authority 裁决）+ `AccessExemptEndpoints`（豁免清单：服务代理面 `/v1/**`、JWT 自助、用量摄取，已认证放行）；未登记端点维持 fail-closed（仅 ADMIN），不放宽任何旧边界 |
| 删除 | `PermissionRuleRegistry`/`PermissionAuthorizationManager`/`PermissionCodes`/`PermissionRule`/`RbacUnmatchedPolicy`/`RbacExemptEndpoints`/`RbacEndpointCoverage*`/`RolePermissionSeeder`/`RolePermissionService`/`RolePermissionEntity/Repository`/`PermissionManagementController`/`RbacEndpointCoverageHealthIndicator` + 16 个 RBAC 测试 |
| 数据层 | V17（h2+postgres）`DROP TABLE IF EXISTS role_permissions` |
| JWT | 停发 `permissions` claim；`JwtAuthentication` 只携带角色 authority；刷新链路只重算角色（`JwtRefreshIdentityResolver` 收敛为单来源，`JwtRefreshClaimResolver` 仍剔除旧 permissions 防复活） |
| 前端 | 权限码降级为「可见性映射键」：新增 `config/permissionMatrix.ts`（码→最低逻辑角色，30+ 处 `hasPermission` 调用点零改动）；`stores/user.ts` 删 permissions claim 解析；删 `PermissionManagement.vue` 及「设置→账号与权限→权限管理」子页签、`api/permission.ts` |
| 顺手修正的两个存量不一致 | ①「调用观测」（`GET /api/call-history/**`）：旧码 `callhistory:view` 不在运维模板，运维打开必 403 → 登记为 OPS；②「全部密钥」（`/api/auth/api-keys/**`）：旧码 `security:apikeys:manage` 被运维模板排除 → 登记为 OPS |
| 测试 | 新增 `FixedRoleAccessPolicyTest`（矩阵契约）+ `FixedRoleAuthorizationManagerTest`（三档裁决/豁免/API-Key 主体/fail-closed）；schema 门控测试归一化扩展（删实体后 H2 自动约束名移位，`CONSTRAINT_<hex>` 一并归一化） |
> - 老 Redis key `trunkline:quota:*` → 双读过渡到 `trunkline:default:quota:*`
> - 老 JWT → 缺 `tenantId` 时按 `'default'` 处理

---

## 六、阶段 S3：生产稳定性与安全（3 周）

> 目标：让开源版能扛住企业自部署，压测报告出来，安全审计过一遍。
> **告警边界（2026-11-03 决策）**：告警 Webhook 推送归商业版（任务 74），社区版不做；本阶段的稳定性承诺为「控制台内故障可见」——状态监控页一眼看清挂了什么（熔断 3 色卡、上游健康表、最近异常表）。

| # | 任务 | 预估 |
|---|---|---|
| 45 | PostgreSQL 生产压测：单节点 1000 RPS → 目标 3000 RPS，出压测报告（JMeter/wrk） | 8h |
| 46 | Redis Cluster/Sentinel 部署验证，出高可用部署文档 | 6h |
| 47 | 优雅停机测试：发 SIGTERM 后 in-flight 请求处理完再退出 | 4h |
| 48 | 备份恢复演练：H2 → PG 迁移备份、Redis RDB/AOF、DB pg_dump 恢复 | 4h |
| 49 | 安全审计：扫 SQL 注入（MyBatis/JPA）、路径遍历、SSRF（下游 base-url 用户可控）、JWT 密钥强度、CORS 配置 | 8h |
| 50 | 依赖漏洞扫描：`mvn dependency-check:check`，log4j/spring 等高危 CVE 升级 | 4h |
| 51 | Docker 镜像扫描：trivy 扫基础镜像漏洞，distroless 验证 | 2h |
| 52 | 生产部署文档补全：k8s 非 Helm 的手动 manifests、Nginx 反代配置、TLS 终止 | 6h |
| 53 | 日志规范：敏感信息（API Key、Prompt、用户数据）在日志里脱敏 | 4h |
| 53a | 控制台故障可见性自检：断开一个上游实例，验证运维**无需翻菜单**即可在首页/状态监控看到熔断/下线信息（社区版验收清单项的工程化落实） | 4h |

---

## 七、阶段 S4：配置减负 + 员工激活（持续）

> 目标：靠开源版口碑获客。主题明确为「让管理员配得快、让员工用得上」——这两件事分别保住 TTV 和员工激活率。

### 7.1 原有功能增强

| # | 任务 | 预估 |
|---|---|---|
| 54 | 新 Provider Adapter：DeepSeek/智谱/月之暗面/通义 原生 adapter（现在是 openai-compatible 配置，做原生优化） | 8h/个 |
| 55 | 语义缓存（向量相似度复用）——README 已列"另立项" | 12h |
| 56 | 模型用量按 token 类型拆分（prompt/completion/cache_read/cache_write）统计——为商业版费率表铺路 | 6h |
| 57 | 【2026-11-03 移交 ENT → 任务 74】Webhook 通知（决策：告警外推归商业版，社区版不做） | — |
| 58 | ✅ 调用记录导出 CSV/JSON（2026-11-04 落地）——后端 `CallHistoryExportService`（管理员 `/api/call-history/export` 带筛选条件 + 员工自助 `/api/call-history/mine/export` 归属服务端强制；**只导元数据不导正文**防 Prompt 明文外流，RFC4180 转义 + UTF-8 BOM 防 Excel 乱码，封顶 10000 行防内存打爆）；前端「调用观测」页导出下拉按钮，导出范围与页面所见筛选一致 | 4h |
| 59 | 国际化完善：英文文档站、控制台英文校验 | 8h |

### 7.2 配置减负与员工激活（社区版规划追加）

| # | 任务 | 说明 | 预估 |
|---|---|---|---|
| 59a | 接入向导 3 步化 | 41 字段表单改 ①选厂商 ②填密钥 ③确认；仅 8 个必填前置，其余进「高级设置」折叠面板 | 2 天 |
| 59b | 三张 17 列表格瘦身 | 默认列 ≤7（员工/用途/可用模型/本月已用/到期/状态/操作），次要列收进展开行；列选择记忆 localStorage | 3 天 |
| 59c | 员工侧术语收尾 | 「虚拟模型」→「可用模型」；消除中英混排；用 `employeeTerms.ts` 禁用词清单跑检查脚本 | 1 天 |
| 59d | 员工激活包 | 管理员批量导入员工 + 首登强制改密 + 密钥配置扫码预填——SSO 归 ENT 前的社区版过渡方案，直接攻「第二套凭证」摩擦 | 3 天 |

> **59d 处置（2026-11-04 用户决策）**：批量导入账号 UI（`BatchImportAccountsDialog.vue`）与快速上手二维码已落地（2.5/3）；**「首次登录强制改密」与「批量导入+随机初始密码」暂缓不做**（动认证敏感区，用户拍板先搁置）。59d 整体标记为延后，剩余项待后续评估。
| 59e | glossary 全站覆盖 | 术语词典 10 词 → 30 词，`TermHelp` 接入全部含技术词的运维/管理员页面 | 2 天 |

---

## 八、商业版 MVP（v1.0.0-ENT，10-12 周，与 S2 起并行）

> 目标：第一个能收费的商业版，通过闭源 JAR 分发。任务范围与 `editions.md` 的 🧭 标注模块一一对应。

| # | 任务 | 线 | 预估 |
|---|---|---|---|
| 60 | `Tenant` 实体 + 租户管理员自服务门户 + 平台运营后台 | ENT | 3 周 |
| 61 | 数据行级隔离：所有业务表带 tenant_id，JPA 拦截器自动拼条件（注：V6 Flyway 与 TenantJpaFilter 已在 S2 任务 35-36 预埋，此任务只做管理 UI） | ENT | 1 周 |
| 62 | OIDC SSO（飞书/企业微信/Azure AD）——对应对照页 🧭「SSO 单点登录」 | ENT | 2 周 |
| 63 | SAML 2.0 + SCIM 2.0——对应对照页 🧭「SSO 单点登录」 | ENT | 2 周 |
| 64 | 模型费率表 + 成本售价分层 | ENT | 1.5 周 |
| 65 | 多维账单报表（月/租户/部门/Key/模型）+ CSV/PDF 导出 | ENT | 1.5 周 |
| 66 | 部门/项目级预算 + 多级告警（80%/100%） | ENT | 1 周 |
| 67 | 外部 Moderation Provider 接入（OpenAI Moderation/阿里云内容安全）——对应对照页 🧭「内容安全」 | ENT | 1.5 周 |
| 68 | **流式 SSE 实时审核 + 断流**（核心差异化）——对应对照页 🧭「内容安全」 | ENT | 2 周 |
| 69 | 审计记录防篡改（append-only 表 / 哈希链）——对应对照页 🧭「不可篡改审计」 | ENT | 1 周 |
| 70 | 审计冷数据归档 S3 + SIEM 外发（Syslog/OTel Logs/Webhook）——对应对照页 🧭「不可篡改审计」 | ENT | 1.5 周 |
| 71 | 闭源 JAR 打包 + 安装文档 + 许可证校验（注：LicenseVerifier 接口与 Noop 已在 S2 任务 32-33、43 预埋，此任务只做 RSA 校验实现与商业 license 签发流程） | ENT | 1 周 |
| 72 | RBAC 细粒度增强：数据级权限（按部门/项目圈定可见模型与账单）+ 高危操作审批流——对应对照页 🧭「RBAC 细粒度增强」 | ENT | 2 周 |
| 73 | Helm Chart + Operator 交付：values 一键部署、滚动升级、备份钩子——对应对照页 🧭「K8s 高可用交付」（S3 任务 52 的手动 manifests 保留为社区版文档） | ENT | 2 周 |
| 74 | 告警 Webhook 通知：配额超限、熔断打开、实例下线推送到飞书/钉钉/Slack/通用 webhook，可配置重试与静默窗口——对应对照页 🧭「告警 Webhook 通知」（原社区版任务 57，2026-11-03 移入） | ENT | 1 周 |

> **对照页边界核对（2026-11-03）**：editions.md 七个 🧭 模块的排期落位——SSO→任务 62/63；内容安全→任务 67/68；不可篡改审计→任务 69/70；License 校验→任务 43/71；RBAC 细粒度增强→任务 72；K8s Operator 交付→任务 73；告警 Webhook→任务 74（原社区版任务 57 移入）。无未排期的承诺项。

---

## 九、社区版明确不做（防范围蔓延）

| 能力 | 归属 | 依据 |
|---|---|---|
| SSO 单点登录 | ENT | `editions.md` 🧭 |
| 多租户 | ENT | `editions.md` 🧭 |
| 告警 Webhook 推送 | ENT（任务 74） | 2026-11-03 决策 |
| 不可篡改审计 / 合规报告导出 | ENT | `editions.md` 🧭 |
| RBAC 细粒度增强（数据级权限/审批流） | ENT | `editions.md` 🧭 |
| Operator 化交付 | ENT（社区版保留手动 manifests 文档） | `editions.md` 🧭 |

## 十、升版闸门（0.1.0 → 0.2.0 的验收清单）

以下 7 条全过才升版；升版时同步执行品牌统一动作（Docker 镜像 tag `trunkline/trunkline`、CHANGELOG 首版、GitHub Release）：

- [ ] 干净环境 `docker run` 后跑 `scripts/ttv-check.sh`，TTV ≤ 10 分钟
- [ ] 部署后 7 天员工激活率 ≥ 60%（种子客户实测）
- [ ] 断开一个上游实例，运维**无需翻菜单**即可在控制台首页/状态监控看到熔断/下线信息
- [ ] 接入一个新厂商 ≤ 3 步向导，必填项 ≤ 8 个
- [ ] 三张核心表格（全部密钥/模型配置/虚拟模型）首屏列数 ≤ 7
- [ ] 员工侧页面 grep 不到 `employeeTerms.ts` 禁用词（Token/IP Hash/令牌桶/OPEN/CLOSED/权限码）
- [ ] `mvn verify` 400 测试全绿 + CI 连跑 3 次稳定

---

## 十一、本周立即做（S1 启动）

1. 建 NOTICE 文件
2. 跑一次 `mvn verify` 确认 400 个测试全绿
3. 全局替换 `jairouter.` → `trunkline.`（配置 + Java + 文档），加 legacy 兼容映射
4. 改 pom.xml 的 docker.image.prefix
5. 重写 README
6. 建 GitHub 公开仓库
7. 跑通干净环境 clone → build → run 全流程 + `scripts/ttv-check.sh` 记录 TTV 基线

## 十二、风险提示

| 风险 | 应对 |
|---|---|
| 配置前缀 455 处替换可能漏 | 替换后全仓 grep `jairouter` 必须 0 结果（除 NOTICE 和 README 声明二开关系的句子） |
| 前端新旧控制台并行易混淆 | S2 必须收编完，旧控制台加 deprecation 横幅 |
| 400 个测试可能有 flaky | 升版前在 CI 上连跑 3 次全绿才算过 |
| Flyway 生产脚本不可逆 | V1 已发布不可改，后续 schema 变更只增 V6/V7... |
| 商业版代码误提交开源仓库 | S2 拆 Maven module 后，CI 加 pre-commit hook 检查 `trunkline-enterprise` 包名 |
| 版本冻结期间用户误以为项目停滞 | 用 commit tag + 里程碑 issue 标注阶段进度；README 顶部说明版本策略 |
| 孤儿页面误恢复路由暴露技术化 UI | 归档进 `_attic/` 后在目录加 README 说明「已退役，勿直接恢复路由」，恢复前必须走人话化改造 |

---

## 附录 A：代码实况底账（2026-10-28 探查，修正认知用）

### A.1 已经验证做对的

| 项 | 代码实况 |
|---|---|
| **数据库迁移** | 完整 Flyway：`V1__baseline.sql`、`V2__legacy_convergence.sql`、`V5__quota_ledger_group_dimension.sql`，H2 对应齐全；生产 `ddl-auto=validate` |
| **配置模块化** | `application.yml` 只做 import，拆成 20+ 模块 yml |
| **测试覆盖** | `src/test/java` 下 **400 个测试文件**，含关键路径测试 |
| **配额原子性** | `RedisCounterBackend` 的 `INCREMENT_WITH_LIMIT_SCRIPT` 为 Lua 原子脚本；`LocalCounterBackend` 用 `synchronized(slot)` + 预留锁 |
| **多 profile** | dev / prod / staging / legacy / migrate 五个 profile |
| **前端页面** | 70+ 个 .vue 文件，覆盖全部模块 |
| **外部配置挂载** | Docker 支持 `/app/config/` 外部覆盖 |

### A.2 当初低估、实际存在的问题

| 项 | 代码实况 |
|---|---|
| **配置前缀残留** | `jairouter.` 在 96 个文件里出现 455 次 |
| **Docker 镜像前缀** | `pom.xml` 的 `docker.image.prefix=jairouter` |
| **明确未实现的功能** | `MonitoringController.java:221`、`DataSyncServiceImpl.java:438`、`TracingSecurityConfig.java:27` 零消费方字段 |
| **前端新旧控制台并行** | 旧控制台 `8080/admin` 仍在并行运行，新 Vue 控制台分批迁移 |
| **商业版功能零代码** | 全仓无 `Tenant` 实体、`BillingService`、`InvoiceService`、`ModerationService`、`GuardrailService`、`SsoConnector` |
| **tenantId 只是维度标签** | 仅作 Redis key 段出现，无独立 Tenant 聚合根 |
| **作者注释** | 大量 `@author JAiRouter Team`，Trunkline 修改标记缺失 |
