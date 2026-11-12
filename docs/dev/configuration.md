# 配置参考

> 基于 v0.1.0 真实代码。`src/main/resources/config/` 下共 **23 个 yml**（不是 22），`application.yml` 有 23 条 classpath import + 4 条外部挂载。
> 改配置前先看本文的"易踩项"；新增/删除文件见 [扩展指南](extending.md#4-新增配置文件)。

## 1. 优先级链（最重要）

```
1. 模块配置文件（最低）   ← config/ 下 23 个 classpath import
2. 主配置文件             ← application.yml
3. 环境配置               ← application-{profile}.yml（dev / prod / migrate）
4. 环境变量 / 系统属性/命令行参数（最高）
```

**两条铁律**：

- **`spring.config.import` 中后导入的优先**（同名键覆盖前者）。所以外部挂载（`optional:file:/app/config/...`）**必须放在 import 列表末尾**，否则会被 classpath 默认值静默覆盖——这是 issue #634 修过的真实 bug。
- **profile 高于 classpath import**：在 `application-dev.yml` / `application-prod.yml` 里显式写出的键，会压制 `core.yml` 里带环境变量占位符的 `${DATABASE_URL:...}`。切库时留意。

## 2. import 清单与分组地图

`application.yml` 的 23 条 classpath import（顺序即优先级从低到高）：

| # | 文件 | 组 | 管什么 |
|---|---|---|---|
| 1–5 | `common/{server,webclient,logging,scheduling,cluster}.yml` | common | 端口/优雅停机、出站 WebClient、日志、定时任务、多副本集群 |
| 6 | `config-service/core.yml` | config-service | 数据源、JPA、Store 核心 |
| 7–9 | `router/{ratelimit,fallback,services}.yml` | router | 限流、降级、服务与实例 |
| 10–13 | `auth/{jwt,api-key,audit,sanitization}.yml` | auth | JWT、API Key、审计、脱敏 |
| 14 | `base/monitoring-base.yml` | base | 监控指标与 Actuator 端点（逻辑属 monitor） |
| 15–17 | `monitor/{slow-query-alerts,error-tracking,persistence-monitoring-base}.yml` | monitor | 慢查询、错误追踪、JWT 持久化监控 |
| 18 | `tracing/tracing-base.yml` | tracing | 链路追踪 |
| 19 | `persistence/state-persistence-base.yml` | persistence | 熔断/LB 状态三层退坡持久化 |
| 20–22 | `security/{security-base,persistence-base,audit-base}.yml` | security | 安全默认值、持久化、审计明细（**各有独占键**） |
| 23 | `quota/quota-base.yml` | quota | 配额账本 |

外部挂载（必须末尾，4 条）：`optional:file:/app/config/{application.yml,auth/jwt.yml,auth/api-key.yml,router/services.yml}`，对应仓库的 `config-external/` 目录（Docker 挂载到 `/app/config/`）。

## 3. 重点配置详解

### 3.1 router/（恰好 3 个，有守卫）

| 文件 | 关键键 → 生效值 |
|---|---|
| `services.yml` | `model.services.<chat\|embedding\|rerank\|tts\|stt\|imgGen\|imgEdit>.load-balance.type`：chat=least-connections、embedding=round-robin、rerank=ip-hash(+sha256)、tts=random；各服务 `rate-limit` 全开（token-bucket）、instances **全部注释掉**（不内置示例模型，实例由管理页配置） |
| `ratelimit.yml` | `model.rate-limit.enabled=true`（**Java 默认 false，被此覆盖**）、capacity=1000/rate=100、`key-dimension=client-ip`；`trunkline.ratelimit.distributed.enabled=${TRUNKLINE_RATELIMIT_DISTRIBUTED:false}`（Redis 故障 fail-open 放行） |
| `fallback.yml` | `model.fallback.enabled=true`（**Java 默认 false，被此覆盖**）、`strategy=default`（可选 default/cache；yml 注释提到 mock 但代码无此分支） |

### 3.2 security/（3 个各有独占键，故保留）

| 文件 | 关键键 |
|---|---|
| `security-base.yml` | `trunkline.security.api-key.enabled=**false**`、header-name=X-API-Key、`sanitization.response.enabled=false`、`audit.enabled=true`、`cache.redis.enabled=false` |
| `persistence-base.yml` | `store.type=h2`、`jwt.persistence.*`（enabled=false、primary=file、fallback=memory、max-tokens=500）、`jwt.blacklist.persistence.*`、`spring.data.redis.*`（`${REDIS_HOST:localhost}` 等）、`management.health.redis.enabled=${REDIS_ENABLED:false}` |
| `audit-base.yml` | 明细审计开关；**`log-token-details=false` / `log-key-details=false`**（不记完整凭据）；retention jwt 90d / api-key 365d / events 180d |

> ⚠️ **跨文件覆盖实例（演示优先级链）**：`auth/api-key.yml` 设 `api-key.enabled=true`（import 第 10 条），`security/security-base.yml:17` 设 `false`（第 20 条，晚导入）→ 若**只看 classpath import**，后者胜出为 `false`。但 `application-dev.yml:89` 和 `application-prod.yml:71` **都把它重新设为 `true`**（profile 优先级更高），且 `common/server.yml` 默认 `spring.profiles.active=dev` → **实际运行通常是 `true`**。
> 这个例子说明：推演某键最终值必须**完整走一遍**优先级链（import 顺序 → profile → 环境变量），只看到中间一层就下结论会出错。

### 3.3 auth/

| 文件 | 关键键 |
|---|---|
| `jwt.yml` | `jwt-header=Trunkline_Token`（仅认此头）、`secret=${JWT_SECRET:${DEV_JWT_SECRET:}}`（**无内置默认密钥**，空则启动期生成随机值）、HS256/60min/issuer=trunkline、`accounts: []`、`blacklist-fail-closed-when-unavailable` **未设置**（取 Java 默认 false） |
| `api-key.yml` | `enabled=true`（但见上面覆盖冲突）、header=X-API-Key、cache 1h、refresh 60s、`keys: []`（非 dev 环境出现 `sk-test-` 前缀直接拒绝启动） |
| `audit.yml` | `audit.enabled=true`、include-request/response-body=false、retention 90d |
| `sanitization.yml` | `response.enabled=false`（**默认关**）、PII 正则 3 条、masking-char `*`、`preserve-json-structure=true` |

### 3.4 config-service/core.yml

`store.type=jpa`、path=`./config`、fallback=memory、migration.enabled=true；
`spring.datasource.url=${DATABASE_URL:jdbc:h2:file:./data/trunkline;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=FALSE}`；
`spring.jpa.hibernate.ddl-auto=update`（**唯一例外**：连 PostgreSQL 时被强制覆盖为 `validate`）；H2 console 默认关。

### 3.5 其余分组速览

| 文件 | 关键键（默认值） |
|---|---|
| `common/server.yml` | port=8080、graceful shutdown 30s、`spring.main.allow-circular-references=false`、`spring.profiles.active=dev`（默认 profile） |
| `common/webclient.yml` | 实际生效 `trunkline.webclient.*`：connect 10s、response 60s、max-connections 500（宽松绑定，`TRUNKLINE_WEBCLIENT_*` 直接覆盖，无需占位符） |
| `common/scheduling.yml` | `distributed-lock.enabled=true`（Redis setIfAbsent 排他锁，无 Redis 降级单实例） |
| `common/cluster.yml` | `replicas=${TRUNKLINE_REPLICAS:0}`、`shared-state-check.mode=${TRUNKLINE_SHARED_STATE_MODE:WARN}` |
| `base/monitoring-base.yml` | `management.endpoints...include`、health probes=liveness/readiness（**刻意不含 db/redis**）、`health.redis.enabled=${REDIS_ENABLED:false}` |
| `quota/quota-base.yml` | `trunkline.quota.enabled=true`（**Java 默认 false，被此覆盖**）、fail-open=true、windows=[MINUTE,HOUR,DAY,MONTH] |
| `tracing/tracing-base.yml` | `enabled=true`、sampling=ratio 0.1、thread-pool core 8/queue 8192（曾积累 33 个死键，现有绑定守卫锁定） |

## 4. profile 机制

| profile | 文件 | 用途 |
|---|---|---|
| `dev` | `application-dev.yml` | **默认**；H2 控制台、Swagger 全套、DEBUG 日志 |
| `prod` | `application-prod.yml` | 生产收紧：WARN 日志、`include: health,info,prometheus`、fail-closed |
| `migrate` | `application-migrate.yml` | 迁移专用：非 web 应用、跑完退出（常与 prod 组合 `prod,migrate`） |
| ~~staging~~ | 已移除 | 守卫测试断言其不存在，**勿恢复** |

### dev vs prod 关键差异

| 维度 | dev | prod |
|---|---|---|
| 日志 | DEBUG | root/org=WARN，落 `logs/trunkline-prod.log` |
| Actuator | `include: "*"`、details always | `include: health,info,prometheus`、details when-authorized |
| H2 控制台 | true | false |
| `store.migration.enabled` | 继承 true | **false** |
| `shared-state-check.mode` | WARN | **FAIL**（多副本+共享态未开则 fail-fast） |
| `jwt.secret` | `${JWT_SECRET:${DEV_JWT_SECRET:}}` | `${JWT_SECRET:${PROD_JWT_SECRET:}}`（两者皆无则报错） |
| `jwt.issuer` | trunkline | **trunkline-prod** |
| **`blacklist-fail-closed-when-unavailable`** | **false**（可用性优先） | **`true`** |
| `sanitization.response.enabled` | false | **true** |
| `audit.log-level` / include-body | DEBUG / true | WARN / false / alert-enabled true |

> **prod 的 fail-closed 已确证为 true**：`application-prod.yml`（约 64 行）显式设置，`ProdBlacklistFailClosedBindingTest` 用真实 `YamlPropertySourceLoader + Binder` 断言 prod=true、基础 jwt.yml=false（证明来自 profile 覆盖而非 Java 默认被改）。
> ⚠️ **便携版默认 dev profile**，此加固不在便携包生效。生产/多用户部署务必以 prod 启动或显式设 `TRUNKLINE_SECURITY_JWT_BLACKLIST_FAIL_CLOSED_WHEN_UNAVAILABLE=true`。

## 5. 配置绑定与环境变量

### 5.1 两套注册机制并存

- `@ConfigurationPropertiesScan` 只扫 `config` 与 `monitor.callhistory.config` 两个包。
- `@Component + @ConfigurationProperties` 的类（如 `SecurityProperties`、`QuotaProperties`，在 auth 包）走组件扫描，**不在**上述 Scan 包内。

主要 prefix：`model` → `ModelRouterProperties`；`trunkline.security` → `SecurityProperties`；`trunkline.quota` → `QuotaProperties`；`trunkline.tracing` → `TracingConfiguration`；`trunkline.call-history` → `CallHistoryProperties`；`monitoring` → `MonitoringProperties` 等。

### 5.2 Java 默认 ≠ yml 实际默认（易误解）

| 键 | Java 字段默认 | yml 基线 | 最终生效 |
|---|---|---|---|
| `trunkline.quota.enabled` | false | quota-base.yml true | **true** |
| `model.rate-limit.enabled` | false | ratelimit.yml true | **true** |
| `model.fallback.enabled` | false | fallback.yml true | **true** |
| `blacklist-fail-closed-when-unavailable` | false | 基础不设 / prod true | dev=false / **prod=true** |

> 判断某个键"默认是多少"要看**最终生效值**，不能只照抄 Java 字段初始值或注释。

### 5.3 占位符与宽松绑定

- 占位符统一 `${ENV:default}`。密钥用嵌套回退：`${JWT_SECRET:${DEV_JWT_SECRET:}}` / `${JWT_SECRET:${PROD_JWT_SECRET:}}`，默认空串。
- **JWT_SECRET 启动期处理**（`StartupSecurityEnvironmentPostProcessor`）：若未显式注入，生成 48 字节 SecureRandom、Base64 落 `./data/jwt-secret`（CREATE_NEW、仅属主可读写），以 addFirst 最高优先级注入。**删 `data/` 会丢密钥导致已签发令牌失效**。
- **宽松绑定**：如 `trunkline.webclient.*` 直接以 `TRUNKLINE_WEBCLIENT_*` 或 `--trunkline.webclient.*` 覆盖，无需在 yml 写占位符。

## 6. 配置结构守卫

`ConfigFileStructureTest` 锁定的硬约束（改动必须同步，否则测试红）：

| 约束 | 断言 |
|---|---|
| 7 个模块目录存在 | auth/common/router/monitor/persistence/config-service/tracing |
| **router 恰好 3 个 yml** | `assertEquals(3, ...)`；且 adapter/loadbalancer/circuitbreaker/pools/rules 5 个**必须不存在**（防复活） |
| auth ≥ 4 个 | jwt/api-key/audit/sanitization |
| import 链含 5 个关键条目 | auth/jwt、router/ratelimit、common/server、config-service/core、monitor/slow-query-alerts |
| dev/prod/migrate 存在，**staging 必须不存在** | 防恢复 |
| dev/prod 各 **< 150 行** | 从原 763 行精简而来 |
| 旧 `config/monitoring/` 目录不存在 | 已迁移到 monitor |
| 配置总数 **≥ 20**（当前 23） | — |

另有 `TracingConfigBindingGuardTest` 用 `NoUnboundElementsBindHandler` 严格绑定 tracing-base.yml——**新增任何配了没人读的键都会让测试变红**。

## 7. 删 yml 的判断标准

> **逐键比对「最终生效值 vs Java 默认值 / 其他文件同值」：若一个文件的全部键都没有"独占生效值"，即可删除，由 Java 默认值或保留文件兜底，行为不变。**

已据此删除：`base/server-base.yml`、`base/model-services-base.yml`（与 common/*、router/* 同值重复）、`router/{adapter,loadbalancer,circuitbreaker,pools,rules}.yml`（值同 Java 默认）、`application-staging.yml`（无部署入口激活）。

**反向判据**：保留必须有"独占键/独占生效值"——security 三文件即因此保留。

**操作口诀**：删前逐键确认每个值都能在 ①Java 字段默认 或 ②保留的 yml/profile/环境变量 找到同值覆盖 → 删文件 → 同步 `application.yml` import + `ConfigFileStructureTest`（含 `assertFalse` 防复活）+ 绑定守卫测试。
⚠️ 特别注意 `quota.enabled`、`rate-limit.enabled`、`fallback.enabled` 这类"Java 默认 false 但 yml 基线 true"的键——实际默认藏在 yml 里，删错会**静默改变行为**。

## 8. 易踩项清单（速查）

1. **文件数别数错**：config/ 实为 **23** 个 yml；application.yml 有 23 条 classpath import + 4 条外部挂载。
2. **import 后者优先**：`security-base.yml`(后) 的 `api-key.enabled=false` 盖掉 `auth/api-key.yml`(前) 的 `true`（但 dev/prod profile 又盖回 `true`，见 3.2 节——务必推演完整优先级链）；外部挂载必须放末尾。
3. **profile 高于 import**：dev/prod 显式写的键压制 core.yml 的 `${DATABASE_URL:...}`。
4. **prod fail-closed=true，dev/基础=false**；非 prod profile 启动仍 fail-open。
5. **SERVER__PORT 双下划线劫持端口**：非项目正式变量（正式是 `SERVER_PORT`），但会话/CI/桌面环境注入会经宽松绑定覆盖 `server.port`。打包脚本统一 `unset/Remove-Item SERVER__PORT, SERVER__HOST` + `--server.port=8080` 兜底（**系统属性/命令行参数 > 环境变量**）。
6. **Java 默认 ≠ yml 实际默认**：见第 5.2 节三例。
7. **占位符空值**：JWT 默认空串，靠启动处理器生成 `./data/jwt-secret`；**删 data/ 丢密钥**。
8. **死键静默不生效**：Spring `ignoreUnknownFields=true`，写错/写死键不报错只不生效；改键名前先找消费方（`@ConfigurationProperties` 字段 / `@Value` / `@ConditionalOnProperty`）。
9. **health 探针组刻意不含 db/redis**：否则 Spring Boot 因 `HealthEndpointGroupMembershipValidator` 在无组件时**启动失败**。
10. **Redis 健康探针默认关**（`REDIS_ENABLED:false`）：未部署 Redis 时保持 `/actuator/health` 不为 DOWN。
11. **守卫测试硬约束**：router 恰好 3、总数 ≥20、dev/prod <150 行、删 staging/旧 monitoring 目录会判红。
12. **非 dev 环境 `sk-test-` 前缀 Key 拒绝启动**；`keys: []` 不可随意加测试 Key。

---

Trunkline Team · Apache-2.0 · 基于 JAiRouter
