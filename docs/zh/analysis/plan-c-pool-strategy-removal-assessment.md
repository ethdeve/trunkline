# 方案 C 评估：彻底移除 per-serviceType 负载均衡机制，保留全局策略，改用虚拟模型（池）策略

> 评估对象：Trunkline 网关控制台（`D:\wdx\wjy\wjy-ai-gateway`，二次改造规范库）
> 评估日期：2026-11-02
> 结论摘要：**架构方向可行且正确，但「彻底移除」不可行 —— 应改为「保留机制、降级为内部兜底 + 收敛 UI 入口」。**

---

## 一、待评估的方案定义

方案 C = 移除「多模型分工（per-serviceType 负载均衡）」整套机制，同时：

- **保留**全局策略（`model.load-balance`）；
- 改为**虚拟模型（池）级策略**作为唯一的策略配置面；
- 池策略覆盖全局策略。

---

## 二、核心结论

### 2.1 架构方向：✅ 可行，且比现状更合理

| 判据 | 现状（per-serviceType） | 方案 C（池级） |
|---|---|---|
| 配置对象 | `model.services.<serviceType>.load-balance`（8 处 yml + DB `loadBalanceType` 列） | 池（虚拟模型）上的 `strategy` 字段 |
| 配置粒度 | 按**模型类别**（chat/embedding/…） | 按**具体虚拟模型** |
| 语义正确性 | ❌ 同一类别下所有虚拟模型被强制同策略 | ✅ 每个虚拟模型可独立设策略（例：GPT-4o 走轮询、Claude 走最少连接） |
| 落库 | 服务管理页写 DB（`ServiceConfigManager:311` → `entity.setLoadBalanceType()`） | `PoolPersistenceService.saveAll` 整体序列化 `PoolDefinition`（含 `strategy`），**已落库** |
| 热生效 | 需重启副本 | `PUT /api/config/pools/{name}` → `reloadActivePools()` → `poolSelector.reloadPools()`，**已支持** |
| 前端 UI | 已存在（`PoolManagement.vue:142` `el-select v-model="form.strategy"`，5 选项） | **已存在，无需新建** |
| 与 R17 单线治理的一致性 | ⚠️ 割裂（策略按类别，路由按池） | ✅ 一致（策略与路由同在池上） |

**结论：池级策略在数据模型、持久化、热生效、前端 UI 四个层面均已闭环，方案 C 不需要新建任何基础设施。**

### 2.2 但「彻底移除」不可行 —— 三个硬阻塞

#### 阻塞 1：全局策略的兜底对象必须从 serviceType 迁移到池

当前兜底链（`ConfigConverterHelper.getEffectiveLoadBalanceConfig:518-528`）：

```
serviceConfig.loadBalance  →  globalConfig  →  default(random)
```

方案 C 移除 per-serviceType 后，必须改成：

```
pool.strategy  →  globalConfig  →  default(random)
```

**问题**：`PoolDefinition.strategy` 默认值是硬编码 `"weighted-random"`（`PoolDefinition.java:21`），**不是 null**。这意味着：

- 现有池若未显式配置 strategy，`getStrategy()` 永远返回 `"weighted-random"`，**全局策略永远不会被读到**；
- 全局策略形同虚设，而用户以为「池没配就用全局」。

**必须做的改造**：把 `PoolDefinition.strategy` 默认值改为 `null`（或空串），并让 `ModelServiceRegistry` 在 `matchedPool.getStrategy()` 为空时回落到 `properties.getLoadBalance()`。

#### 阻塞 2：`hashAlgorithm` / `virtualNodes` 在池级无处安放

现状这两个参数**只在 per-serviceType 通道可配**：

- `PoolDefinition` **没有** `hashAlgorithm` / `virtualNodes` 字段（已核实）；
- `getLoadBalancerByStrategy(strategy)`（`LoadBalancerManager:272-292`）只传 `type`，`hashAlgorithm`/`virtualNodes` 走 `LoadBalanceConfig` 默认值（`md5` / `150`）；
- 前端池编辑页只有 5 个策略名的下拉，**没有**哈希算法/虚拟节点输入框；
- 而 `LoadBalancerGlobalConfig.vue` 里的 hashAlgorithm/virtualNodes 控件是**纯展示、只读**（无保存按钮，`/config/global` 后端只有 GET 无 PUT）。

**结果**：方案 C 若彻底移除，**ip-hash / consistent-hash 的哈希算法与虚拟节点数将永久无法配置**（只能改 yml 重启）。这是**功能能力净损失**，不是等价重构。

**必须做的改造（三选一）**：

- 选项 i：给 `PoolDefinition` 加 `hashAlgorithm` / `virtualNodes` 字段 + 前端表单控件（工作量中等，但语义完整）；
- 选项 ii：保留全局级 hashAlgorithm/virtualNodes，给全局策略补一个**可写的持久化通道**（补 `PUT /api/loadbalancer/config/global` → 落库）；
- 选项 iii：接受该能力降级为「改 yml」，并在 UI 明确标注（不推荐，属于静默能力丢失）。

#### 阻塞 3：规则锁定实例路径 + 状态恢复路径需要 per-serviceType LB

**(a) 规则锁定实例路径**（`ModelServiceRegistry.java:374-401`）

当规则引擎以 `TARGET_INSTANCE`/`TARGET_MODEL` 显式锁定实例时（管理员内部动作），请求**不命中池**、走 else 分支，此时用 `getLoadBalancer(serviceType)` 兜底选实例。移除后该路径无 LB 可用。

**(b) 状态恢复路径**（`StateRecoveryService.java:203/274`）

按 **serviceType** 恢复 LB 内部状态（轮询计数器、least-connections 连接数等）。而池策略的 LB 是按 **strategy 名**缓存的（`LoadBalancerManager.strategyBalancers`），键空间不同 —— 状态恢复口径天然错配。

> 注：`SelectedInstanceHolder`（`ModelServiceRegistry:471/540`）已保证回调时用回**选择时同一个** LB 实例，正常路径不依赖 per-serviceType LB；此风险仅在 holder 为空的边缘路径。

---

## 三、被遗漏的第三条策略通道：规则的 LB_STRATEGY 动作

规则引擎有独立的策略覆盖通道，**优先级高于池策略**：

```
ModelServiceRegistry.java:367-373（池分支内）
    if (ruleDecision.getLbStrategy() != null)
        loadBalancer = loadBalancerManager.getLoadBalancerByStrategy(ruleDecision.getLbStrategy());
```

- 数据来源：`RuleDefinition.lbStrategy`（`RuleDefinition.java:111`），前端可配（`rules.ts` 的 `LB_STRATEGY` 动作，中文名「流量拆分」）；
- **方案 C 绝不能动这条通道**，否则规则的「流量拆分」动作静默失效。

这条通道与池策略共用 `getLoadBalancerByStrategy`，所以**保留它 = 保留 `strategyBalancers` 缓存 = 保留按策略名建 LB 的能力**。也就是说，`LoadBalancerManager` 不能整体删除，只能删除 per-serviceType 的那一半。

---

## 四、「全局策略保留」的三个具体问题

### 4.1 全局策略当前**不可写**

- `GET /api/loadbalancer/config/global` 存在，**`PUT` 不存在**（已核实 `LoadBalancerManagementController` 只有 `@GetMapping("/config/global")`）；
- `LoadBalancerGlobalConfig.vue` 里的下拉是纯展示，无保存按钮；
- 结论：全局策略目前只能靠改 `model-services-base.yml` + 重启生效。

方案 C 若把全局策略提升为「池策略之上的全局兜底」，**必须先给它补一条可写且落库的通道**，否则这个「全局」在 UI 上完全无效。

### 4.2 全局策略的落点键名

全局策略在 `model-services-base.yml`：`model.load-balance`（`type`/`hash-algorithm`），**不是** `trunkline.*`。注意该 yml 里 `hash-algorithm` 用 kebab-case，而 `LoadBalanceConfig.hashAlgorithm` 是驼峰 —— Spring Boot relaxed binding 可正常映射，但**手写 JSON/YAML 时需注意**（与 S5 修的 `security.performance.cache.redis.enabled` 同类键名陷阱）。

### 4.3 8 处 per-serviceType yml 会变成死配置

`model-services-base.yml` 有 8 处 `load-balance:` 段（L9 全局 + L40/70/110/136/156/175/194 各 serviceType）。方案 C 若移除机制，这些必须**连同 yml 一起清掉或明确标注为「仅兜底」**，不能留半截死配置 —— 与 S5 已修的 `security.performance.cache.redis.enabled` 是同一类问题。

---

## 五、方案 C 的完整改动清单（若按字面执行）

### 后端

| # | 文件 | 改动 | 风险 |
|---|---|---|---|
| 1 | `PoolDefinition.java` | `strategy` 默认值 `"weighted-random"` → `null` | 低 |
| 2 | `PoolDefinition.java` | 新增 `hashAlgorithm` / `virtualNodes` 字段（若选阻塞 2 选项 i） | 低 |
| 3 | `ModelServiceRegistry.java:357-362` | 池 strategy 为空时回落全局 `properties.getLoadBalance()` | 低 |
| 4 | `ModelServiceRegistry.java:374-401` | 规则锁定实例路径：改用全局策略建 LB | **中** |
| 5 | `LoadBalancerManager.java` | 删 per-serviceType `loadBalancers` EnumMap + `getServiceLoadBalanceConfig` + `initializeLoadBalancers` 中的 per-serviceType 循环；保留 `strategyBalancers` | **中** |
| 6 | `LoadBalancerManager.java:227-245` | 删 `reinitializeLoadBalancer(serviceType, ...)`（tab 保存路径） | 低 |
| 7 | `LoadBalancerManagementController.java` | 删 `PUT /config/{serviceType}`；`GET /config/global` 保留；**新增** `PUT /config/global`（落库） | 中 |
| 8 | `StateRecoveryService.java:203/274` | 状态恢复键空间从 serviceType 改为 strategy 或池 | **中高** |
| 9 | `model-services-base.yml` | 删 7 处 per-serviceType `load-balance` 段，保留全局段 | 低 |
| 10 | `ServiceConfigManager.java:311` + DB | `loadBalanceType` 列停用（**零 FK 原则，需应用层校验是否可弃用**） | 中 |
| 11 | `ServiceConfigConverter` / `ServiceConfigValidator` / `ConfigMerger` | `loadBalance` 字段的读写路径清理 | 中 |

### 前端

| # | 文件 | 改动 |
|---|---|---|
| 12 | `StrategyView.vue` | 删 multiModel tab（`TABS` 移除 `'multiModel'`） |
| 13 | `views/load-balancer/StrategyConfig.vue` | **整文件删除** |
| 14 | `views/load-balancer/globalConfigShared.ts` | 清理 inject 逻辑（仅 StrategyConfig 用），保留 provide 侧 |
| 15 | `views/config/ServiceManagement.vue:232-242` | 删 per-serviceType `loadBalance.type` 表单项（否则留半个功能） |
| 16 | `views/config/pools/PoolManagement.vue` | 池策略下拉旁加说明 +（若选阻塞 2 选项 i）补 hashAlgorithm/virtualNodes 控件 |
| 17 | `views/load-balancer/LoadBalancerGlobalConfig.vue` | 补保存按钮（需后端 PUT /config/global 支撑） |
| 18 | `locales/{zh-CN,en-US}/shell.json:32` | 删 `multiModel` |
| 19 | `locales/{zh-CN,en-US}/loadBalancer.json` | 清理 `strategyConfig` 段冗余文案 |
| 20 | `locales/{zh-CN,en-US}/service.json` | 清理 `loadBalance*` 表单项文案 |

### 测试

| # | 文件 | 改动 |
|---|---|---|
| 21 | `ConsoleGetSurfaceCoverageTest.java:117-124` | 删/改 8 个 `/api/loadbalancer/**` 端点断言（`PUT /config/{serviceType}` 删后需调整） |
| 22 | `LoadBalancerManagementControllerTest.java` | 删 `updateServiceConfig` 相关测试 |
| 23 | `ConfigFileStructureTest.java` | 引用了 loadbalancer，需核对 |
| 24 | **新增** | 「池策略优先于全局策略」回归测试（当前**完全缺失**） |
| 25 | **新增** | 「池 strategy 为空时回落全局」回归测试 |

> 改动点数量：**后端 11 类 + 前端 9 类 + 测试 5 类 ≈ 25 处**，其中 4 处标「中/中高」风险。

---

## 六、推荐执行方案：C′（改良版方案 C）

**保留机制的骨架，只收敛「配置面」与「语义」**：

| 动作 | 内容 | 保留什么 |
|---|---|---|
| ① 语义归位 | 池 `strategy` 默认值改 `null`；池 strategy 为空时回落全局 | 让全局策略真正成为兜底 |
| ② 配置面收敛 | 删「多模型分工」tab + `ServiceManagement.vue` 的 per-serviceType 策略项 + 7 处 yml 段 | UI 上只剩「虚拟模型页」一个配置面 |
| ③ 机制保留 | **不删** `LoadBalancerManager.loadBalancers` / `getLoadBalancer(serviceType)` / 规则锁定实例路径 / 状态恢复路径 | 边缘路径（规则直锁实例、状态恢复）继续可用 |
| ④ 全局可写 | 补 `PUT /api/loadbalancer/config/global` + 前端保存按钮 + 落库 | 让「全局兜底」在 UI 上真正可用 |
| ⑤ hash 参数 | 给 `PoolDefinition` 加 `hashAlgorithm`/`virtualNodes` + 池表单控件 | 不丢能力 |
| ⑥ 测试 | 补「池策略优先」「回落全局」两条回归测试 | 锁定新语义 |

**与原方案 C 的差异**：不删机制（省掉 #4/#5/#6/#8/#10/#11 及全部中高风险项），但达成同样的用户目标 —— **用户只在虚拟模型页配策略，全局页配兜底**。

---

## 七、最终判断

| 问题 | 答案 |
|---|---|
| 用虚拟模型策略架构上可行吗？ | ✅ **可行**。数据模型、持久化、热生效、前端 UI 四层均已闭环，无需新建基础设施；且比 per-serviceType 更符合「虚拟模型 = 对外调用单元」的 R17 治理语义 |
| 能「彻底移除」per-serviceType 机制吗？ | ❌ **不能**。① 规则锁定实例路径需要兜底 LB；② 状态恢复键空间与 strategy 名缓存错配；③ 规则的 LB_STRATEGY 通道共用 `strategyBalancers`，删了会连带废掉「流量拆分」动作；④ hashAlgorithm/virtualNodes 无处安放 |
| 「保留全局策略」可行吗？ | ⚠️ 需先补一条可写落库通道（当前只有 GET），否则全局策略仍是「只能改 yml」的死配置 |
| 推荐做法 | **方案 C′**：语义归位 + 配置面收敛 + 机制保留 + 全局可写 + hash 参数下沉 + 补回归测试。约 12-15 处改动，中低风险 |

---

## 附：证据索引

| 结论 | 证据 |
|---|---|
| 池策略已接线并生效 | `ModelServiceRegistry.java:357-359` |
| 池策略落库 | `PoolPersistenceService.saveAll:38-49`（Jackson 整体序列化 PoolDefinition） |
| 池策略前端已暴露 | `PoolManagement.vue:142-150`（el-select form.strategy，5 选项） |
| 池策略默认非 null | `PoolDefinition.java:21`（`= "weighted-random"`） |
| 池级无 hash 参数 | `PoolDefinition` 无 `hashAlgorithm`/`virtualNodes` 字段 |
| 池 LB 只传 type | `LoadBalancerManager.getLoadBalancerByStrategy:272-292` |
| per-serviceType 仅兜底一条路径 | `ModelServiceRegistry.java:374-401` |
| 规则 LB_STRATEGY 通道 | `ModelServiceRegistry.java:367-373`、`RuleDefinition.java:111` |
| 全局策略不可写 | `LoadBalancerManagementController` 仅 `@GetMapping("/config/global")` |
| 全局策略 yml 落点 | `model-services-base.yml:9`（`model.load-balance`） |
| 8 处 per-serviceType yml | `model-services-base.yml:40/70/110/136/156/175/194` |
| tab 保存不落库 | `LoadBalancerManagementController.updateServiceConfig:223-251`（仅 `reinitializeLoadBalancer`） |
| per-serviceType 落库通道在别处 | `ServiceConfigManager.java:311-314`（`entity.setLoadBalanceType`）+ `ServiceManagement.vue:232-242` |
| 状态恢复按 serviceType | `StateRecoveryService.java:203/274` |
| 端点断言会红 | `ConsoleGetSurfaceCoverageTest.java:117-124` |
