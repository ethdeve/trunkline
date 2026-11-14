# 扩展指南

> 基于 v0.1.0 真实代码。类名路径相对 `src/main/java/org/unreal/modelrouter/`（测试类相对 `src/test/java/`）。
> 通用环境/命令/提交规范见 [CONTRIBUTING.md](../../CONTRIBUTING.md)，架构背景见 [架构速览](architecture.md)。
>
> **本指南的核心价值**：把散落在代码注释和测试断言里的"必须同步改的点"集中出来。Trunkline 用大量**守卫测试**锁定一致性，只改一半必然测试变红——下面每一项都标了要跑哪个测试。

## 1. 新增供应商模板

供应商模板有**两个真相源**，必须一致：

| 端 | 文件 | 要点 |
|---|---|---|
| 后端（真实源） | `router/adapter/template/AdapterTemplateService.initTemplates()`（约 239–375 行） | 内置 13 个模板；用 `createTemplate(...)` 组装，`source=builtin` |
| 前端（fallback） | `frontend/src/constants/providerPresets.ts` 的 `TEMPLATE_SOURCES` | 13 条，与后端同名/id/baseUrl/models/type 一一对应 |

**当前 13 个**：国内 7（deepseek/zhipu/moonshot/qwen/minimax/stepfun/siliconflow）+ 国际 5（openai/gemini/groq/openrouter/together）+ 本地 1（local-ollama）。

### 操作步骤

1. 后端 `initTemplates()` 增加一条 `templates.add(createTemplate(...))`，分配**唯一 id** 与 **sortOrder**。
2. 前端 `providerPresets.ts` 的 `TEMPLATE_SOURCES` 加对应条目（注意 `PROVIDER_PRESETS` = 13 模板 + 1 个 `uid:'custom'` 入口，长度是 14，别数错）。
3. 改下面两个测试类里**所有**数量断言（共 10+ 处硬编码）：

| 测试 | 断言点 |
|---|---|
| `router/adapter/template/AdapterTemplateServiceTest` | TMPL-001 总数=13、TMPL-005 国内=7、TMPL-006 国际=5、TMPL-007 本地=1、TMPL-016=14（13 内置+1 自定义）、TMPL-021 |
| `router/adapter/template/AdapterTemplateHideRestoreTest` | HR-001=13、HR-002 隐藏 1 个=12、HR-003 隐藏 2 个=11、HR-005 恢复=13、HR-006 重复隐藏=12 |

4. 验证：

```bash
./mvnw test -Dfrontend.build.skip=true -Dtest=AdapterTemplateServiceTest,AdapterTemplateHideRestoreTest
cd frontend && npm run type-check
```

### 硬约束与坑

- **内置模板只支持两种协议**：`openai-compatible` / ollama-compatible（`validateTemplate()` 校验）。需要原生认证头的厂商**不能**做成内置模板，必须走"自定义适配器"。
- **Gemini 走官方 OpenAI 兼容端点** `https://generativelanguage.googleapis.com/v1beta/openai`，认证是 `Authorization: Bearer`，**不是** `x-goog-api-key`；原生 Gemini 协议走 `router/adapter/impl/GeminiAdapter`（key 走 `x-goog-api-key` 头）。前端 `providerPresets.ts` 的 `inferPath()` 正则已含 `v\d+beta\/openai`，别再补 `/v1`。
- **不要新增 Claude 内置模板**：Claude 是原生协议（仅 `x-api-key` + `anthropic-version: 2023-06-01`），由 `router/adapter/impl/ClaudeAdapter` 处理，不属模板范畴。
- **uid 是幂等标识，不可随意改**：builtin 直接用模板 id，custom 加 `tpl-` 前缀（`providerPresets.ts` 的 `templateToPreset()` / `customTemplateToPreset()`）。改它会让历史落库配置对不上。
- **内置模板不可被改/删**：`updateTemplate()` / `deleteTemplate()` 对 `source=builtin` 直接抛异常。

## 2. 新增负载均衡或降级策略

先澄清命名：仓库里**没有** `RoutingStrategy` / `FilterStrategy` 类。承载策略的是下列体系。

### 2.1 新增负载均衡算法

- 实现接口 `router/loadbalancer/LoadBalancer`（`selectInstance(...)`），放到 `router/loadbalancer/impl/`（已有 RR/随机/一致性哈希/IP哈希/最少连接/延迟感知/粘性）。
- **必改分发点**：`router/factory/ComponentFactory.createLoadBalancer()` 的 `switch(config.getType().toLowerCase())`，`default` 回退 random 并告警。
- 有状态策略（如轮询计数器）必须经 `LoadBalancerManager.getLoadBalancerByStrategy()` 创建并缓存，保证跨请求复用同一实例。
- 若需配置字段，加 `ModelRouterProperties.LoadBalanceConfig`。
- 验证：补对应 `*Test`，跑 `./mvnw test -Dfrontend.build.skip=true -Dtest=*LoadBalancerTest`。

### 2.2 新增降级策略

- 实现 `router/fallback/FallbackStrategy<T>`。**注意请求维度默认方法** `fallback(Exception, String requestKey)`（M-24 防跨请求串扰）：按精确请求键命中缓存，找不到就 503，**绝不取任意一条缓存**。
- **必改分发点**：`router/factory/ComponentFactory.createFallbackStrategy()` 的 `switch`（当前只识别 `cache` / `default`；yml 注释写了 `mock` 但代码中无分支，未知值一律走 default）。
- 同步 `FallbackConfig.strategy` 注释与 `config/router/fallback.yml` 注释。
- 验证：`./mvnw test -Dfrontend.build.skip=true -Dtest=DefaultFallbackStrategyTest,CacheFallbackStrategyTest`。

### 2.3 改重试语义

- 类：`router/adapter/retry/RetryPolicy`。`canRetry(retryCount, error, effectiveMaxRetries)` 按服务类型收敛预算（`getMaxRetriesByServiceType()` chat/embedding=2，其余=1）；`isRetryable()` 仅放行 408/429/5xx/网络故障，4xx 其余不重试。
- 验证：`./mvnw test -Dfrontend.build.skip=true -Dtest=RetryPolicyTest,RetryPolicyClientErrorTest`。

### 2.4 配置映射

router 配置只有 3 个 yml（`config/router/{services,ratelimit,fallback}.yml`），映射到 `ModelRouterProperties`（prefix `model`）的内部类。其余 5 个文件的键（adapter/loadbalancer/circuitbreaker/pools/rules）的默认值**内置在 Java 代码里**。**router 恰好 3 个文件是硬断言，不得新增或复活**，见[配置参考](configuration.md#6-配置结构守卫)。

## 3. 权限扩展（两道闸门）

Trunkline 的权限是**两道闸门、同口径**，这是最容易踩坑的地方（历史上出过"URL=OPS、方法=ADMIN 导致 OPERATOR 403"）。

### 闸门一：URL 层固定角色矩阵

- 权威源：`auth/security/access/FixedRoleAccessPolicy` 的 `buildRules()`，用 `AccessRule.get/write/any(path, level)` 登记。
- **首条命中优先**（`findRule()` 顺序遍历）。更具体的路径必须排在通配之前：
  - `/api/call-history/mine` 必须在 `/api/call-history/**`(OPS) 之前；
  - `/api/monitoring/quota/mine/usage`(SELF) 必须在 `/api/monitoring/quota/**`(OPS) 之前；
  - 实例「我的」是 `/api/instance/mine`，别写成 `/api/config/instance/mine`（会被 `/api/config/instance/**` 吞掉）。
- 级别：`auth/security/access/AccessLevel` —— SELF（三角色）/ OPS（运维+管理员）/ ADMIN（仅管理员）。
- 裁决：`FixedRoleAuthorizationManager.check()` —— 命中矩阵按 level 裁决 → 查豁免清单 → **都未命中 fail-closed，仅 ADMIN**。
- 豁免清单：`auth/security/access/AccessExemptEndpoints.defaultExemptions()`，每条**必须写明 reason**。含 `/api/v1/**` 服务代理面、JWT 自助端点、`/api/models`、`/api/token-usage/record[/batch]`。

### 闸门二：方法级 @PreAuthorize

- 项目开启 `@EnableReactiveMethodSecurity`；注解**只对返回 `Mono`/`Flux` 的方法生效**。
- 用法示例：`ApiKeyManagementController`（运维方法 `hasAnyRole('OPERATOR','ADMIN')`、自助方法三角色）、`JwtTokenController.getTokens()`（`#userId == authentication.name` 约束属主）。
- **同步返回 `ResponseEntity` 的控制器严禁加 `@PreAuthorize`** —— 在响应式方法安全下会抛 500。这条由代码注释 + 反射测试双重锁定：
  - `DocsController`（第 54 行）、`SetupController`、`MyServiceInstanceController`、`TokenUsageController`、`ApiCallHistoryController` 均注明；
  - `FixedRoleAuthorizationManagerTest.docsControllerReliesOnUrlPolicyOnly()` 断言 `getContent` 无 `@PreAuthorize` 且 URL 层为 OPS。

### 新增端点的动作

1. 二选一登记访问级别：`FixedRoleAccessPolicy.buildRules()` 加规则（**注意顺序**）；或 `AccessExemptEndpoints` 加带 reason 的豁免。都不做则默认仅 ADMIN。
2. 控制器返回 `Mono`/`Flux` 时，再用 `@PreAuthorize` 做属主校验作第二道闸；同步 `ResponseEntity` **禁加**。
3. 前端菜单/路由的 `meta.roles` 同步（如新增 ops/admin 页面）。
4. 验证：

```bash
./mvnw test -Dfrontend.build.skip=true \
  -Dtest=FixedRoleAccessPolicyTest,FixedRoleAuthorizationManagerTest,ApiDocsAccessPolicyTest
```

> `FixedRoleAuthorizationManagerTest.MethodSecurityConsistency` 会用反射逐方法断言注解与角色一致，两道闸门口径不一致会直接失败。

## 4. 新增配置文件

1. 在 `config/<模块>/` 新增 yml。
2. **同步 `application.yml` 的 `spring.config.import`**（外部挂载项须留在列表末尾，因为"后导入优先"）。
3. **同步 `ConfigFileStructureTest`** 对应模块的存在性/数量断言。特别注意：
   - router 目录 `assertEquals(3, ymlFiles.length)`，且 5 个已删文件（adapter/loadbalancer/circuitbreaker/pools/rules）有 `assertFalse` 防复活；
   - `application-staging.yml` 必须不存在（防恢复）；
   - dev/prod 各 < 150 行、配置总数 ≥ 20。
4. 验证：`./mvnw test -Dfrontend.build.skip=true -Dtest=ConfigFileStructureTest`。
5. 删文件的判断标准见 [配置参考](configuration.md#7-删-yml-的判断标准)。

## 5. 实体变更与数据库迁移

**当前状态（2026-11-14）：项目尚未发版、零存量，V1 是唯一基线** —— `db/migration/{h2,postgres}` 下各只有 `V1__baseline.sql`，schema 变更直接改两侧 V1（见下表「尚未发版」行），不留增量脚本、不留存量升级包。原 V2–V18 已归档到 `db-migration-archive/`（不进 jar）；原 V2/V3（#653 索引、成交价快照列）已并入 V1，`db/stock-upgrade/` 存量升级包已删除。**正式发版后**才切换为「已发布基线不可改」：Flyway 按校验和判定，schema 变更新增 `V2__…` / `V3__…`。

| 情形 | 操作 |
|---|---|
| 基线**已发布** | 新增 `postgres/V2__xxx.sql` **和** `h2/V2__xxx.sql`，两侧同名同版本号，仅物理类型不同（H2 用 json/TEXT/enum，PG 用 jsonb/TEXT/varchar+check） |
| 尚未发版 | 直接改两侧 `V1__baseline.sql`（可参考文件头的 Hibernate schema 导出命令重新生成） |

- 守卫：`H2BaselineMatchesEntityModelTest` —— 同一内存库先由实体元数据 `ddl-auto:create` 快照，再由 Flyway 跑 `db/migration/h2` 快照，两次必须逐项一致（tables/columns/constraints/indexes）。失败信息直接给出操作指引。
- PG 侧由 `spring.jpa.hibernate.ddl-auto=validate` 在启动时校验（`FlywayDialectEnvironmentPostProcessor` 强制覆盖为 validate）。
- 验证：`./mvnw test -Dfrontend.build.skip=true -Dtest=H2BaselineMatchesEntityModelTest`；PG 一致性通过切 PG 启动验证。

详见 [数据模型](data-model.md)。

## 6. 代码风格与质量门禁

| 项 | 约束 | 验证 |
|---|---|---|
| 版权头 | 新增 Java 文件手写 `Copyright (c) 2026 Trunkline Team. All Rights Reserved.` + `@author Trunkline Team`；继承 JAiRouter 的文件保留原署名（见 NOTICE）。**注意：这是人工/评审约定，非 CI 强制** | 评审 |
| Checkstyle | `checkstyle.xml`（行宽 ≤120、禁 Tab、禁星号 import、switch 带 default 等），大量豁免见 `config/suppressions.xml`；`severity=warning`、`failsOnError=true` | `mvn checkstyle:check` |
| SpotBugs | `spotbugs-maven-plugin` + findsecbugs，`threshold=Medium`、`maxRank=15` | `mvn spotbugs:check` |
| 一键质量检查 | `make quality-check`（= checkstyle + spotbugs） | — |

> ⚠️ **不要用 `-Pfast`/`-Pdev`/`-Pprod` 验证质量**：这些 profile 会 `checkstyle.skip=true`、`spotbugs.skip=true`、`jacoco.skip=true`。默认构建（无 profile）才真正执行。
> 提交前最低要求（`CONTRIBUTING.md`）：`./mvnw test` 与 `cd frontend && npm run type-check` 通过。

## 扩展场景速查表

| 场景 | 必改主文件 | 必须同步 | 验证测试 |
|---|---|---|---|
| 新增供应商模板 | `AdapterTemplateService.initTemplates()` | `providerPresets.ts` 的 `TEMPLATE_SOURCES`；数量断言 | `AdapterTemplateServiceTest`、`AdapterTemplateHideRestoreTest` |
| 新增负载均衡算法 | `router/loadbalancer/impl/` 新类 + `ComponentFactory.createLoadBalancer()` | `ModelRouterProperties.LoadBalanceConfig`、`LoadBalancerManager` | 对应 `*LoadBalancerTest` |
| 新增降级策略 | `router/fallback/impl/` 新类 + `ComponentFactory.createFallbackStrategy()` | `FallbackConfig`、`fallback.yml` 注释 | `DefaultFallbackStrategyTest`、`CacheFallbackStrategyTest` |
| 改重试语义 | `RetryPolicy` | — | `RetryPolicyTest`、`RetryPolicyClientErrorTest` |
| 新增端点权限 | `FixedRoleAccessPolicy.buildRules()` 或 `AccessExemptEndpoints`（带 reason） | 控制器（Mono 才加 `@PreAuthorize`；同步 ResponseEntity 禁加）、前端菜单 | `FixedRoleAccessPolicyTest`、`FixedRoleAuthorizationManagerTest` |
| 新增配置文件 | `config/<模块>/*.yml` | `application.yml` import；`ConfigFileStructureTest` | `ConfigFileStructureTest` |
| 实体变更 | JPA 实体 + `postgres/V*.sql` | `h2/V*.sql`（两侧同版本；已发布新增 V2__，未发版改 V1） | `H2BaselineMatchesEntityModelTest` + PG validate |

---

Trunkline Team · Apache-2.0 · 基于 JAiRouter
