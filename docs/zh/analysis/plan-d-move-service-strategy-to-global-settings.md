# 方案 D 评估：把「多实例（模型类型）策略配置」迁入「全局配置 → 负载均衡」tab

> 评估对象：Trunkline 网关控制台（`D:\wdx\wjy\wjy-ai-gateway`，二次改造规范库）
> 评估日期：2026-11-02
> 结论：**✅ 架构上完全可行，且是原本的设计意图；推荐采用。** 相比方案 C′ 改动量更小、风险更低。

---

## 一、结论速览

| 维度 | 判断 |
|---|---|
| 架构可行性 | ✅ **可行**——这正是 `globalConfigShared.ts` 注释里写的原始设计（"全局配置页把『负载均衡全局默认配置』与『各服务类型策略列表』放在同一页"） |
| 运行时影响 | ✅ **零**——纯前端位置迁移，不动后端选择逻辑 |
| 权限影响 | ✅ **零**——组件级 `usePermission('lb:config:write')` 随组件一起走 |
| 改动量 | ✅ **小**——前端 4-5 文件，后端 0 |
| 遗留问题 | ⚠️ 搬完后「策略中心」只剩路由规则一个 tab；per-serviceType 配置**不落库**的既存缺陷仍在（需一并修） |

---

## 二、为什么这个方案是对的：它本来就是原设计

`frontend/src/views/load-balancer/globalConfigShared.ts` 的文件注释写得非常明确：

> 「全局配置」页把「负载均衡全局默认配置」与「各服务类型策略列表」放在同一页，
> 后者需要读前者的值（列表的「配置」弹窗以全局值为默认值回退），
> 因此用 provide/inject 在同页内共享。

而 `GlobalSettingsView.vue` 的注释同样写着：

> **负载均衡** —— 全局默认策略 / 哈希算法 / 虚拟节点数。
> 原为「策略中心 → 负载均衡」tab 的第一张卡片；**各服务类型的策略列表仍留在策略中心**。

**即**：v3.5 改造时把「全局默认卡片」搬到了全局配置页，但把「各服务类型策略列表」留在了策略中心，导致 `provide/inject` 的两端被拆到两个页面 —— `LoadBalancerGlobalConfig.vue` 在全局配置页 `provide`，`StrategyConfig.vue` 在策略中心 `inject`，**跨页面注入实际拿不到值**（`StrategyConfig.vue:181` 的 `inject` 必然返回 `null`，所以才有 `loadGlobalConfigIfNeeded()` 兜底再请求一次）。

**结论：这个方案不是新设计，而是把被拆散的原设计合回去，顺带修掉一个跨页面 inject 的隐性缺陷。**

---

## 三、可行性逐项核实

### 3.1 页面结构：✅ 天然适配

`GlobalSettingsView.vue` 的「负载均衡」tab 现在只挂一个 `<LoadBalancerGlobalConfig />`（L17-19），结构非常干净：

```vue
<el-tabs v-model="active">
  <el-tab-pane label="记录治理" name="record"><RecordGovernanceSettings /></el-tab-pane>
  <el-tab-pane label="负载均衡"  name="lb"><LoadBalancerGlobalConfig /></el-tab-pane>   ← 迁入点
  <el-tab-pane label="熔断阈值" name="circuit"><CircuitBreakerGlobalConfig /></el-tab-pane>
  <el-tab-pane label="用量配额" name="quota"><QuotaConfigManagement /></el-tab-pane>
</el-tabs>
```

迁移后「负载均衡」tab 内变成两张卡片：**① 全局默认策略**（现状）+ **② 各模型类型策略**（新增）——语义自洽：上面是兜底、下面是逐类覆盖。

### 3.2 权限：✅ 无错配（有先例可循）

| 层 | 现状 | 迁移后 | 结论 |
|---|---|---|---|
| 路由准入 | `settings/global` → `meta: { permissions: ['system:accounts:manage'] }`（router/index.ts:191） | 不变 | ✅ 不动 |
| 组件级写权限 | `StrategyConfig.vue:140` → `hasPermission('lb:config:write')` | 随组件迁移 | ✅ 不变 |
| 后端端点权限 | `PermissionRuleRegistry:137-138` → `GET /api/loadbalancer/**` = `LB_MONITORING_READ`，`WRITE` = `LB_CONFIG_WRITE` | 不变 | ✅ 不动 |

**关键先例**：同页其它 tab 正是这么做的 ——
- `CircuitBreakerGlobalConfig.vue:112` → `hasPermission('config:circuitbreaker:write')`（**不是** 路由级的 `system:accounts:manage`）
- `QuotaConfigManagement.vue` → 组件内 `canWrite` 控制写按钮

即：**路由级权限只管"能不能进这个页面"，组件级权限管"能不能改这一项"**。迁移后沿用 `lb:config:write` 完全符合既有模式，无错配。

**角色覆盖不变**（`RolePermissionSeeder`）：ADMIN 全量 / OPERATOR 所有 :read+:write（含 `lb:config:write`）/ USER 与 VIEWER 只读。

### 3.3 运行时：✅ 零影响

迁移只改 UI 位置，**不碰**后端任何选择逻辑：
- `ModelServiceRegistry.selectInstance` 的池策略分支（L357-359）不动；
- `LoadBalancerManager` 的 EnumMap 与 `getLoadBalancerByStrategy` 不动；
- `PUT /api/loadbalancer/config/{serviceType}` 端点不动（前端调用路径不变，只是页面换了）。

### 3.4 provide/inject：✅ 反而修复了隐性缺陷

迁移后 `LoadBalancerGlobalConfig`（provide）与策略列表（inject）**回到同一页面树**，`provide/inject` 真正生效：
- `StrategyConfig.vue:181` 的 `inject` 不再返回 `null`；
- `loadGlobalConfigIfNeeded()` 的兜底重复请求可移除；
- 弹窗默认值回退（`showConfigDialog:300-302`）读的是同一份实时值，**不会出现「全局改了但列表显示旧值」**。

---

## 四、改动清单

### 前端

| # | 文件 | 改动 | 风险 |
|---|---|---|---|
| 1 | `views/settings/GlobalSettingsView.vue` | 「负载均衡」tab 内加挂策略列表组件（`<StrategyConfig />` 或抽 `<ServiceStrategyList />`） | 低 |
| 2 | `views/load-balancer/StrategyConfig.vue` | 从 `StrategyView.vue` 的 tab 中摘出；改为全局配置页内的独立卡片组件（去掉 PageSkeleton 外壳，改用 `el-card`，与同页其它 tab 风格一致） | 低 |
| 3 | `views/strategy/StrategyView.vue` | 删 multiModel tab；`TABS` 移除 `'multiModel'`；`active` 默认值处理 | 低 |
| 4 | `views/load-balancer/globalConfigShared.ts` | provide/inject 现在真正生效 → `StrategyConfig.vue` 里的 `loadGlobalConfigIfNeeded()` 兜底可简化 | 低 |
| 5 | `locales/{zh-CN,en-US}/shell.json:32` | 删 `multiModel`（tab 消失） | 低 |
| 6 | `locales/{zh-CN,en-US}/loadBalancer.json` | 新增全局配置页内的「各模型类型策略」区块文案（表格列名、按钮、说明） | 低 |

### 后端

**无改动。**（可选增强见第五节）

### 测试

| # | 文件 | 说明 |
|---|---|---|
| 7 | `ConsoleGetSurfaceCoverageTest.java:117-124` | **不受影响** —— 端点全部保留，只是 UI 换位置 |
| 8 | 后端单测 | **不受影响** |

> 对比方案 C′（后端 11 类 + 前端 9 类 + 测试 5 类 ≈ 25 处，含 4 处中高风险），方案 D 只有 **6 处前端改动 + 0 后端 + 0 测试改动**。

---

## 五、迁移后仍需处理的两个遗留问题（与迁移本身解耦，可分批做）

### 遗留 1：per-serviceType 配置**不落库**（既存缺陷）

`LoadBalancerManagementController.updateServiceConfig:223-251` 只调 `loadBalancerManager.reinitializeLoadBalancer(type, config)`（改内存 EnumMap），**无任何持久化调用** → **重启即失**。

对比：真正落库的 per-serviceType 入口在 `ServiceManagement.vue:232-242` → `PUT /config/type/services/{type}` → `ServiceConfigManager:311-314` → `entity.setLoadBalanceType()` → JPA。

**处理建议（二选一）**：
- **推荐**：迁移后**只保留虚拟模型页（池策略）作为唯一可配入口**，per-serviceType 列表降级为**只读展示**（明确标注"仅展示实际生效值，配置请到虚拟模型页"）——因为池策略才是真正生效的那条路径（见 `plan-c-pool-strategy-removal-assessment.md` 的结论）；
- 或：给 `PUT /api/loadbalancer/config/{serviceType}` 补持久化（工作量中等，且仍与池策略语义重叠）。

### 遗留 2：全局默认策略不可写

`GET /api/loadbalancer/config/global` 存在，**`PUT` 不存在**；`LoadBalancerGlobalConfig.vue` 的下拉是纯展示无保存按钮 → 全局策略只能改 yml 重启。

**处理建议**：既然迁移后全局配置页成了负载均衡的唯一入口，**顺手补上全局策略的保存能力**（后端加 `PUT /config/global` 落库 + 前端加保存按钮），否则这一页「看着能改、实际存不住」。

---

## 六、方案 D 与方案 C′ 的对比

| 维度 | 方案 C′（收敛配置面+保留机制） | **方案 D（迁入全局配置页）** |
|---|---|---|
| 改动量 | 12-15 处（后端 6 + 前端 6 + 测试 2） | **6 处（纯前端）** |
| 运行时风险 | 中（池默认值改 null 会影响所有池的兜底语义） | **零** |
| 达成「配置集中」 | ✅ | ✅ |
| 达成「策略只在虚拟模型配」 | ✅ | ❌（仍保留 per-serviceType 可配入口） |
| 修跨页面 inject 隐性缺陷 | — | ✅ |
| 后续仍需清理 per-serviceType | 是 | 是（可选做只读降级） |

**关系**：方案 D 与方案 C′ **不冲突，可叠加**。D 先做（低风险、立刻改善信息架构），C′ 后做（彻底收敛语义）。

---

## 七、最终建议

**采纳方案 D**，理由：

1. **零运行时风险、零后端改动**，是一次纯信息架构调整；
2. **合回原设计**（`globalConfigShared.ts` 注释明证），顺带修掉跨页面 `provide/inject` 失效的隐性缺陷；
3. 权限沿用同页既有模式（组件级 `usePermission`），零错配；
4. 为方案 C′ 的「配置面收敛」铺路——策略先集中到一屏，后续再把 per-serviceType 降级为只读、最终只留虚拟模型页一个入口，路径最短。

**建议执行顺序**：

| 阶段 | 内容 | 验证 |
|---|---|---|
| **第 1 步** | 迁移 UI：策略列表进全局配置页「负载均衡」tab；策略中心删 multiModel tab | 前端 `vue-tsc --noEmit` + `vite build` 双 EXIT=0；手工点一遍全局配置页负载均衡 tab 与策略中心 |
| **第 2 步** | 顺手把全局策略改成可写（补后端 `PUT /config/global` 落库 + 前端保存按钮） | 后端编译 + 单测；前端双 EXIT=0 |
| **第 3 步**（可选） | per-serviceType 列表降级为只读 + 加"配置请到虚拟模型页"引导 | 前端双 EXIT=0；E2E 走一遍角色链路 |

---

## 附：证据索引

| 结论 | 证据 |
|---|---|
| 原设计就是同页放两者 | `globalConfigShared.ts:5-8` 文件注释 |
| 改造时拆散了两者 | `GlobalSettingsView.vue:44-45` 注释「各服务类型的策略列表仍留在策略中心」 |
| 跨页面 inject 实际失效 | `StrategyConfig.vue:181` inject + `loadGlobalConfigIfNeeded():255-267` 兜底重取 |
| 全局配置页结构 | `GlobalSettingsView.vue:10-30`（4 个 el-tab-pane） |
| 路由级权限 | `router/index.ts:191` → `system:accounts:manage` |
| 组件级权限先例 | `circuit-breaker/GlobalConfig.vue:112` → `config:circuitbreaker:write`；`QuotaConfigManagement.vue` → `canWrite` |
| 后端端点权限 | `PermissionRuleRegistry.java:137-138` |
| 角色覆盖 | `RolePermissionSeeder` L183-186 + operatorCodes/userCodes/viewerCodes |
| 策略列表组件的权限 | `StrategyConfig.vue:140` → `hasPermission('lb:config:write')` |
| 运行时不受影响 | `ModelServiceRegistry.java:357-359`（本次不触碰） |
| 端点测试不受影响 | `ConsoleGetSurfaceCoverageTest.java:117-124`（端点全保留） |
| per-serviceType 不落库 | `LoadBalancerManagementController.updateServiceConfig:223-251` |
| per-serviceType 落库入口在别处 | `ServiceConfigManager.java:311-314` |
| 全局策略不可写 | `LoadBalancerManagementController` 仅 `@GetMapping("/config/global")`，无 PUT |
