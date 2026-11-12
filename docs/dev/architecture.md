# 架构速览

> 基于 v0.1.0 真实代码。类名路径相对 `src/main/java/org/unreal/modelrouter/`（测试类相对 `src/test/java/`）。
> 想动手改代码前，先读 [扩展指南](extending.md)；调配置读 [配置参考](configuration.md)。

## 1. 技术栈

| 层 | 选型 | 备注 |
|---|---|---|
| 运行时 | Java 17（编译目标），Spring Boot 3.5 | `pom.xml` `<java.version>17</java.version>`；打包产物内置 JRE 21，兼容性无问题 |
| Web | Spring WebFlux（响应式） | **不是** Servlet MVC；方法级安全只对返回 `Mono`/`Flux` 生效 |
| 安全 | Spring Security Reactive + JWT + API Key | 双凭据、固定角色矩阵、fail-closed |
| 持久化 | Spring Data JPA + Flyway | H2（内嵌）/ PostgreSQL（生产），双基线脚本 |
| 韧性 | **自研**（非 resilience4j） | 熔断/限流/降级/重试均在 `router` 包 |
| 指标 | Micrometer + Prometheus（`micrometer-registry-prometheus`） | `MeterRegistry` |
| 追踪 | OpenTelemetry 1.35（OTLP / Zipkin / Logging 导出） | W3C TraceContext |
| 健康检查 | Spring Boot Actuator | `/actuator/health` 含 liveness/readiness 探针组 |
| 前端 | Vue 3 + Vite + TypeScript，Element Plus | 构建产物由 Maven 拷入 `static/admin` |

## 2. 模块地图（11 个一级模块）

| 模块 | 职责 | 代表性类 |
|---|---|---|
| `router` | **核心**：协议入口、路由决策、负载均衡、熔断/限流/降级/重试、适配器、响应缓存、Anthropic 协议翻译 | `ServiceRequestHandler`、`ModelServiceRegistry`、`BaseAdapter`、`RuleEngineService` |
| `auth` | 认证鉴权、JWT/API Key 生命周期、配额账本、安全审计、注入检测、响应脱敏 | `SpringSecurityAuthenticationFilter`、`DefaultJwtTokenValidator`、`FixedRoleAccessPolicy` |
| `monitor` | OTel 追踪、Micrometer 指标、调用历史、Token 用量落库、错误/慢查询监控 | `TracingWebFilter`、`DefaultMetricsCollector`、`TokenUsageRecorder` |
| `config` | YAML 与持久化配置合并/校验/版本/同步、实例与服务管理、模型价格、WebFlux/SPA 装配 | `ConfigMergeService`、`ModelPriceController`、`SpaWebFluxConfig` |
| `persistence` | JPA 实体/仓库、Store 抽象（内存/文件）、Flyway 方言、库初始化 | `TokenUsageEntity`、`StoreManager`、`JpaDatabaseInitializer` |
| `enterprise` | 企业版 SPI 扩展点（SSO/计费/审核/租户/许可证）+ 社区版 noop 实现 | `TrunklineEnterpriseAutoConfiguration`、`NoopBillingProvider` |
| `common` | 跨模块契约：统一响应体、全局异常处理、过滤器顺序、循环依赖解耦 API | `RouterResponse`、`ReactiveGlobalExceptionHandler`、`FilterOrderContract` |
| `setup` | 首启初始化向导、启动期安全门禁 | `SetupController`、`StartupSecurityEnvironmentPostProcessor` |
| `billing` | 「我的账单/团队用量」按单价聚合计价（**只读聚合**，不采集用量） | `BillingService` |
| `docs` | 内置帮助文档内容 API | `DocsController` |
| `desktop` | 桌面发行版浏览器启动器 | `DesktopBrowserLauncher` |

## 3. 一句话架构：双协议面 + 单一请求总线 + 自研韧性栈

1. **三个入口、一条总线**：`/v1`（OpenAI 原生面，无包裹）、`/api/v1`（控制台面，`RouterResponse` 包裹）、`/v1/messages`（Anthropic 协议，经翻译器）最终都汇入 `ServiceRequestHandler`。
2. **模型 API 不用 `RouterFunction`**：全项目唯一的 `RouterFunction` 是 `config/core/SpaWebFluxConfig.spaRouterFunction()`，只服务 `/admin/**` 静态资源与 SPA fallback。
3. **韧性分两处生效**：选实例阶段做熔断过滤 + 服务级/实例级/规则级限流；适配执行阶段做重试+故障转移、降级。

## 4. 一次模型调用的完整链路

以 `POST /v1/chat/completions` 为例（其余能力同构）：

```
[1] WebFilter 链（顺序由 common/filter/FilterOrderContract 唯一定义）
    CachedBodyWebFilter → 可写响应头 → WebFluxMetricsInterceptor
      → TracingWebFilter(建根 Span) → Spring Security 链 → InjectionDetectionFilter
      → ResponseSanitizationFilter → TracingSecurityFilter

[2] 认证（auth）
    SpringSecurityAuthenticationFilter.filter()
      → DefaultAuthenticationConverter.convert()  取 X-API-Key / Bearer / Trunkline_Token / query token
      → CustomReactiveAuthenticationManager.authenticate()
           ├ DefaultJwtTokenValidator.validateToken()   JWT
           └ ApiKeyService / ApiKeyValidator            API Key
    成功 → 写入 ReactiveSecurityContext

[3] Controller 入口（router/controller）
    OpenAiNativeController.chatCompletions()  置 NATIVE_RESPONSE_ATTRIBUTE
      → ServiceRequestHandler.handleRequest(CHAT, model, auth, exchange, executor)

[4] 请求总线（router/handler/ServiceRequestHandler）
    权限校验(resolveCallerId / hasServicePermission / hasModelPermission)
      → 生成缓存键 → Mono.defer().subscribeOn(boundedElastic) 把同步段挪出 EventLoop
      → ① 亲和性上下文 → ② 缓存命中预扣限流 → ③ 追踪上下文
      → ④ 读响应缓存（命中即短路）
      → ⑤ reserveQuota() 配额预留
      → ⑥ selectInstance() ★路由决策（见第 5 节）
      → ⑦ 补记实例维度配额 → ⑧ 写调试头 X-Trunkline-Instance/-Pool/-Strategy
      → ⑨ AdapterRegistry.getAdapter() → ⑩ executeWithTracingAndMetrics() 执行

[5] 路由决策（router/model/ModelServiceRegistry.selectInstance）
    ConfigMergeService.getPersistedConfig()
      → RuleEngineService.evaluate()          规则命中可重写模型/锁实例/覆盖 LB
      → SelectInstanceOptimizer.filterByTags() 标签过滤（X-Trunkline-Tags）
      → PoolSelector.findPool()               模型分组（池）路由
      → filterAvailableInstances()→CircuitBreakerManager.canExecute() ★熔断剔除
      → 粘性 LB 包装 → RateLimitManager.tryAcquire() ★服务级限流
      → ServiceInstanceSelector.selectWithRateLimit() ★实例级限流
      → LoadBalancer.select() 策略：RR/随机/一致性哈希/IP哈希/最少连接/延迟感知/粘性
      → 记录选路监控

[6] 适配执行（router/adapter/BaseAdapter）
    checkCapability() → ensureIdempotencyKey()（跨重试/故障转移复用）
      → TracingWebClientFactory 构造下游客户端 → processor 实际 HTTP 调用
      失败 → RetryPolicy.canRetry()（按服务类型预算 + 指数退避）→ tryReselectInstance() 故障转移
      耗尽 → 降级 FallbackManager（无策略/缓存未命中则 503）
    成功（非流式）→ NonStreamingRequestProcessor → TokenUsageExtractor

[7] 用量 / 配额结算（响应环节，请求链内）
    TokenUsageExtractor.extractAndRecordTokenUsage()
      ├ TokenUsageRecorder.recordTokenUsageNoAuth()  ★落 token_usage 表
      ├ ApiKeyService.updateTokenUsage()             ★更新 Key 用量
      └ QuotaReservation.settleSuccess()             ★按实际 token 冲正预留
    失败路径由 QuotaReservation.settleFailure() 回滚

[8] 响应返回 / 异常
    控制台面 → RouterResponse.success(data)；原生面 → 下游 JSON 直返；二进制 → ResponseEntity<byte[]>
    流式 → Flux<ServerSentEvent<String>>（text/event-stream）
    认证/限流错误 → 认证过滤器直接写 401/429（按 V1ErrorBodyMapper 选 OpenAI/Anthropic 形状）
    业务异常 → common/exceptionhandler/ReactiveGlobalExceptionHandler.handle(exchange, ex)
    收尾 → TracingWebFilter.finishHttpSpan()
```

### 关键设计点

- **计量与计费分离**：用量采集在请求链内（`monitor` 的 `TokenUsageRecorder`）；钱数计算在请求链外（`billing` 的 `BillingService` 按 `token_usage` + `model_price` 只读聚合）。
- **配额三段式**：预留 → 补记实例维度 → 按实际冲正；账本默认由 `quota-base.yml` 开启（注意 Java 字段默认值与此不同，见[配置参考](configuration.md)）。
- **线程模型**：同步段（含 JPA/Redis 阻塞调用）用 `subscribeOn(Schedulers.boundedElastic())` 挪出 Netty EventLoop，`finally` 清理多处 ThreadLocal（亲和性/限流标志/追踪上下文/已选实例）。
- **过滤器顺序唯一来源**：`common/filter/FilterOrderContract`；认证链由 Spring Boot 以 `@Order(-100)` 自动注册。

## 5. 韧性四件套速查

| 能力 | 接口/类 | 分发点 | 算法/策略实现 |
|---|---|---|---|
| 熔断 | `router/circuitbreaker/CircuitBreakerManager` | — | `LockFreeCircuitBreaker` + 状态机 `Closed/HalfOpen/Open` + 自适应阈值 |
| 限流 | `router/ratelimit/RateLimitManager` | — | `impl/`：TokenBucket / SlidingWindow / LeakyBucket / WarmUp / RedisSlidingWindow |
| 降级 | `router/fallback/FallbackStrategy<T>` | `factory/ComponentFactory.createFallbackStrategy()` | `DefaultFallbackStrategy`（503）、`CacheFallbackStrategy`（按请求键隔离命中） |
| 重试 | `router/adapter/retry/RetryPolicy` | — | `canRetry()` 按服务类型预算（chat/embedding=2，其余=1）；仅 408/429/5xx/网络故障可重试 |

> 新增这些策略时的完整改动清单见 [扩展指南](extending.md#2-新增负载均衡或降级策略)。

## 6. 可观测性落点

- **Actuator**：`/actuator/health`（含 `liveness`/`readiness` 探针组，刻意 **不含** db/redis，否则无组件时启动失败）、`/actuator/prometheus`、`/actuator/info`。安全规则：health 探针 permitAll，其余 `/actuator/**` 仅 ADMIN。
- **Micrometer**：`monitor/monitoring/collector/DefaultMetricsCollector`，`recordRequest` / `recordBackendCall` / `recordRateLimit` / `recordCircuitBreaker` / `recordCacheTokenUsage`；公共标签 `application=trunkline`。
- **OpenTelemetry**：`monitor/tracing/config/OpenTelemetryAutoConfiguration`（`@ConditionalOnProperty(name="trunkline.tracing.enabled", matchIfMissing=true)`），`TracingWebFilter` 建根 Span，`TracingWebClientFactory` 注入出站，采样策略比率/规则/自适应可运行时切换。追踪跳过 `/actuator/health|metrics|prometheus`。

## 7. 数据与安全边界

- 表结构与迁移约定见 [数据模型](data-model.md)；`V1__baseline.sql` 是已发布基线，不可修改。
- 权限是**两道闸门**：URL 层 `FixedRoleAccessPolicy`（首条命中优先，未登记 fail-closed 仅 ADMIN）+ 方法级 `@PreAuthorize`（仅对 `Mono`/`Flux` 生效；同步返回 `ResponseEntity` 的控制器**禁止**加，否则 500）。详见[扩展指南](extending.md#3-权限扩展（两道闸门）)。
- 品牌/包名等"保留不改"项（如 `org.unreal.modelrouter`、`V1__baseline.sql` 注释）见 [CONTRIBUTING.md](../../CONTRIBUTING.md) 与既有约定。

---

Trunkline Team · Apache-2.0 · 基于 JAiRouter
