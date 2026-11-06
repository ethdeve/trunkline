# Trunkline 企业网关 · 非技术用户与管理员易用性优化方案

> 面向对象：非技术员工（user）、非技术运维管理员（ops）、系统管理员（admin）
> 文档性质：架构勘察 + 产品方案 + 落地计划的汇编
> 代码基线：`D:\wdx\wjy\wjy-ai-gateway`（Spring Boot + Vue3 + Element Plus）
> 成员产出：[代码现状勘察报告](./non-technical-usability-code-audit.md)（高见远）· [产品体验方案](./non-technical-usability-plan.md)（许清楚）
> 汇编：齐活林（主理人）｜2026-11-02

---

## 摘要

Trunkline 的骨架是对的：三角色模型清晰、业务流四段闭环、员工侧术语人话化已落地、密钥页的统计口径与空态出口规范优于同类控制台。

但**本次勘察推翻了五个"看起来没问题"的前提**，并发现**三个会骗人 / 会让额度体系失效的严重缺陷**：

| # | 发现 | 性质 | 证据强度 |
|---|---|---|---|
| **1** | **B 套「价格与额度」是纯 mock** —— 7 条硬编码假数据、「保存」按钮只关弹窗不写库、页面却写着「改完立即生效」。`/models` 不在菜单 → 守卫直接放行，任何登录用户可直达。**用户会基于假数据做成本决策** | **会骗人** | 已实测确认 |
| **2** | **「每月额度」在默认配置下完全不生效** —— 管理员配了 100 万 token 上限，弹出保存成功，员工可无限用；而员工侧「我的额度」还拿这个数字当分母画进度条，显示「已用 30%，额度还有 70%」 | **产品级失效** | 已实测确认 |
| **3** | **`FailureExplainer` 的「去修复」链接指向失效目标** —— `/strategy?tab=circuit`（tab 已迁走）、`/observe?tab=rate`（已无 tab）、`/models`（mock 页）。**「给出口」的机制本身在骗人** | **会骗人** | 已实测确认 |
| **4** | 「接入管理」存在**两套完全可达**的实现（`/providers` 与 `/models`），价格配置能力差 6.8 倍，走后者**配不到熔断与限流** | 入口重复 | 已实测确认 |
| **5** | 「本月已用」在**员工页读 `token_usage`**、在**管理员页读账本**，账本关闭时同一把密钥一个显示数字、一个显示 `—` | 数据源分裂 | 已实测确认 |

另有 **18 条代码级缺陷**（架构师勘察：4×P0 + 6×P1 + 8×P2）与 **69 条产品级改进项**（产品经理 v2：19×P0 + 32×P1 + 18×P2），两者交叉后合并为 **14 条 P0 落地项**（见第二章），其中 **11 条为纯文案/纯前端**（低风险、单周可上线）。

**一条主线判断**：当前体验的主要阻力不是"缺少功能"，而是**界面上呈现的东西与实际生效的东西不一致**。本方案的最高优先级不是加功能，而是**先消除"假控件"和"会骗人的页面"**——让用户看到的数字就是真实的约束。

**同时有三条"能力回退 / 已建成未暴露"的正面发现**（已实测复核）——三态「下一步」引导、完整管理员四步向导、步骤条组件**都已经在代码里**，只是被重构或入口问题埋没了。这意味着引导类改造**不是新建成本，而是接线成本**。

---

## 一、四段业务流的实现现状

用户描述的业务流与代码的对应关系（架构师勘察结果）：

| 业务流 | 菜单入口 | 前端 | 后端 | 现状判定 |
|---|---|---|---|---|
| ① 接入厂商模型 | 团队 → 接入管理 | `providers/ProviderConsoleView.vue`（1236 行）<br>`providers/PricingQuotaPanel.vue`（648 行） | `ModelPriceController`、`InstanceController` | **与描述一致**，价格/额度/熔断/限流集成在一个 tab |
| ② 配置虚拟模型 | 团队 → 虚拟模型 | `config/pools/PoolManagement.vue`（987 行） | `PoolConfigController`、`PoolSelector` | **能力完整**，池策略已端到端闭环（落库 + 热生效 + 5 选项下拉） |
| ③ 创建密钥 | 团队 → 全部密钥 | `security/ApiKeyManagement.vue`（**2931 行**） | `ApiKeyController`、`QuotaEnforcementService` | **能力完整但有假控件**（见 P0-1） |
| ④ 员工使用 | 我的 → 快速上手/测试场 | `my/QuickstartView.vue`、`playground/*` | `/api/auth/api-keys/mine`、`/v1/models` | 术语人话化已落地，空态规范良好 |

**规模基线**：58 个 Controller / 389 个 HTTP 端点 / 前端 views 约 14,675 行 / **18 项菜单**（我的 5 / 团队 8 / 策略 1 / 系统 4）。
> ⚠️ `menu.ts` 文件头注释写「四组 16 项」已过期（团队组实为 8 项），实施时顺手修正。

---

## 二、P0 清单（14 条，11 条纯文案/纯前端）

> **优先级在产品经理 v2 方案后重排**：`P0-0`（mock 页面）与 `P0-A2`（失效的修复链接）两条插到最前 —— 它们不是"缺功能"或"看不懂"，而是**会骗人**。缺功能的页面只让人困惑，会骗人的页面让人基于假数据做决策。

---

### P0-0【会骗人·立即下线】「价格与额度」B 套是纯 mock，改价不写库

**已实测确认**（主理人复核 `ModelPricing.vue` 全文 95 行）：

```ts
/**
 * 模型价格与额度配置
 * TODO: 后端补 model_price 表后，接 GET /api/admin/model-prices
 */
const rows = ref<PriceRow[]>([
  { id: 1, provider: 'OpenAI', modelName: 'gpt-4o', inputPrice: 0.018, ... virtualModels: ['公司主力'], dailyQuota: '不限' },
  ... 7 条硬编码
])
```
- **数据全是硬编码假数据**（含假虚拟模型名「公司主力」「便宜日常」「做知识库」）
- **「保存」按钮只做 `editVisible.value = false`** → **不写库、不调任何 API**
- 页面顶部却写着：「价格按『每 1000 个 Token』计算……**改完立即生效**」← **这是一句谎言**
- 且 `/models` 不在菜单 → 守卫 `menuItem` 为 undefined → **直接放行，任何登录用户可直达**

**后果**：用户进这个页面改价格 → 点保存 → 弹窗关闭 → 刷新还原。**他会以为配置生效了，并据此做成本决策。**

**动作**：立即下线 B 套（`/models` 重定向到 `/providers`）。

> ⚠️ **前置依赖（不可直接删）**：`InstanceManagement.vue`（1296 行）是 admin 唯一的**权重 / 适配器编辑入口**，必须先挂到 A 套抽屉内的 admin 可见链接，再下线 `/models`。详见 Q12。

---

### P0-A2【会骗人】`FailureExplainer` 的「去修复」链接指向失效目标

**已实测确认**（`components/common/FailureExplainer.vue`）：

| 行号 | 目标 | 现状 | 结果 |
|---|---|---|---|
| `:48` | `/strategy?tab=circuit` | `StrategyView.vue:34` 的 TABS = `['rules', 'cache']`，**无 circuit** | 静默落到「路由规则」 |
| `:51` / `:60` | `/observe?tab=rate` | `ObserveView` 已瘦身成单表，**无 tab** | query 被静默忽略 |
| `:69` / `:72` / `:75` | `/models` | P0-0 要下线的 B 套 mock 页 | 跳到假数据页 |
| `:54` / `:57` / `:63` / `:66` | `/keys` | 路由存在但**不在菜单**（孤儿路由） | 能跳，但用户不知道这是哪 |

**「给出口」的机制本身在骗人** —— 一个点了没用的"去修复"链接，比没有链接更糟：它让用户以为问题已被定位。

**动作**：修正落点（circuit → 全局配置熔断 tab；rate → 状态监控；instances → 接入管理）＋ 把 `callHistory.failureExplain.*` 命名空间提到顶层（组件从"某页子组件"升为"全站标准件"，命名空间是组件归属的镜像）＋ 文案双口径（现有「该实例已熔断，暂时被摘除以保护后端」里的"实例""后端"是黑话，直接推给员工会违反 `STAFF_ONLY_TERMS` 验收）。

---

### P0-A【最高危·产品级失效】每月额度在默认配置下完全不生效

**证据链**（架构师实测，主理人复核第 3/4 环）：
1. 管理员在「全部密钥 → 配额」配 `monthlyTokenLimit`（`ApiKeyManagement.vue:249` 绑定 → `ApiKeyCreateRequest.java:115` 落库）
2. 唯一读该字段判定的是 `QuotaEnforcementService.evaluate()`（`:169`）与 `tryReserve()` → `limitsOf()`（`:538`）
3. **`evaluate()` 全仓零调用方**（grep `evaluate(` 只命中定义处与规则引擎同名方法）
4. `tryReserve()` 唯一调用点 `ServiceRequestHandler.java:816`，被 `isEnabled()` 门控 → `QuotaLedgerService.isEnabled()` → `properties.isEnabled()` → **`trunkline.quota.enabled` 默认 `false`**（`quota-base.yml:19`、`QuotaProperties.java:63`）
5. 认证链路的兜底只覆盖 **daily + perMinute**（`ApiKeyValidator.java:205-227`）；`hourlyRequestLimit` / `hourlyTokenLimit` / `monthlyRequestLimit` / `monthlyTokenLimit` **在认证链路里完全没有校验点**
6. 账本关闭时的兜底 `checkEstimatedTokenQuota()` **只判 `dailyTokenLimit`**（`:217`）

**后果**：管理员配「每月 Token 上限 = 100 万」→ 弹出保存成功 → **永远不会触发 429**，员工可无限用。员工侧「我的额度」却拿这个数字当分母（`MyQuotaView.vue:79-82`）画进度条显示「已用 30%」。**越像真的越是误导。**

**修复选项（三选一，需产品+后端拍板）**：
| 方案 | 动作 | 成本 | 风险 |
|---|---|---|---|
| **A. 补认证链路**（推荐） | 仿 `isDailyTokenLimitExceeded` 增 `isMonthlyTokenLimitExceeded`，在 `UsageStatistics` 加月度桶 | 中 | 需确认月度用量统计口径 |
| B. UI 显式禁用 | 账本关闭时，「每小时/每月」类字段禁用 + 提示「当前未启用多窗口配额账本，仅每日与每分钟限额生效」 | **低（纯前端）** | 功能仍缺失，但不再误导 |
| C. 账本默认开启 | `trunkline.quota.enabled` 默认改 `true` | 低 | **需评估性能与存储影响**，且多副本需开 Redis |

> **建议：先做 B（本周可上线、立即消除误导），再做 A（真正兑现价值）。** C 的开关影响面最大，需独立评估。

---

### P0-B 接入管理存在两套完全可达的实现

- **A 套（菜单入口）**：`/providers` → `ProviderConsoleView.vue`（1236 行）→ 价格用 `PricingQuotaPanel.vue`（**648 行**，含熔断/限流/额度与限速）
- **B 套（裸路由）**：`/models` → `ModelsView.vue`（`router/index.ts:55`）→ 3 tab（供应商接入 / 已接入模型 / 💰价格与额度）→ 价格用 `models/ModelPricing.vue`（**仅 95 行**）
- 因 `/models` 不在菜单里 → 守卫 L278 查不到 menuItem → **直接放行**，任何登录用户可直达
- → 走 B 套的人**看得见价格、配不到熔断限流**，且完全不知道还有另一套存在

**动作**：下线 B 套（`/models` 重定向到 `/providers`），保留「已接入模型」tab 并入 A 套。**与 P0-C 合并做**（同一批改 `PricingQuotaPanel.vue`）。

---

### P0-C 接入管理拆出「熔断与限流」tab

现状：`PricingQuotaPanel.vue` 一张 9 列表格承载 4 种治理语义（价格 / 额度 / 熔断 / 限流），熔断列在第 7 列、限流第 8 列。运维看这张表无法回答"多少钱/能扛多快/挂了多久恢复"任何一个问题。且二级弹窗里出现「令牌桶」「scope」等无解释术语。

**动作**：拆为 3 tab —— `接入厂商` / `价格与额度` / `熔断与限流`。

> ✅ **数据已就绪，零后端改动**：`/config/prices/catalog` 返回的 `ModelPriceCatalogItem` 已含 `circuitBreakerEnabled` / `rateLimitEnabled` / `hasPrice` / `enabled` / 全套配额字段。复用现有 `components/CircuitBreakerConfig.vue`、`components/RateLimitConfig.vue`。

---

### P0-D 价格表加「未配价 / 已停用」逐行标记

**真实缺陷**：缺价时按 **¥0 静默计入**（`BillingService.java:92-96` `BigDecimal.ZERO`），不报错。管理员配了价以为账单出来了，实际某个成员模型没配价就在免费跑。

> ✅ **零成本**：`ModelPriceCatalogItem.hasPrice` 与 `.enabled` 字段**已现成**，前端直接用。
> 建议三态：`✓ 已配价` / `⚠ 未配价` / `⏸ 已停用`

---

### P0-E 策略中心菜单副标题改写（承诺 4 件事、只做 1 件）

`menu.ts:175` 写着「路由规则 / 多模型分工 / 模型挂了怎么办 / 新员工默认额度」，而 `StrategyView.vue:34` 实际只有 `['rules']`（响应缓存 feature flag 关闭）。三项承诺中：多模型分工已迁全局配置、模型挂了怎么办是池策略+熔断、新员工默认额度**根本无对应功能**。

**动作**：副标题改为「路由规则——按条件把请求转给不同的模型」；`shell.json` 策略中心标题同步改写。

> ⚠️ **注意**：`menu.ts` 的 `subtitle` 字段**全站从未被渲染**（`ConsoleLayout.vue` L159-180 只渲染 `item.title`）。**改了不显示** → 必须同时加渲染（侧边栏 tooltip）或把说明移到 `PageHeader` 的 subtitle。见 P0-I。

---

### P0-F 虚拟模型列表加「价格状态 / 配额 / 状态」三列 + 成员 chip 价格标签

现状：列表无价格状态列，管理员无法一眼看出"哪个虚拟模型在免费跑"。成员 chip 只有「主/备N」badge + `m.instanceId`（**两种命名混在一列**）。且 `pool.json:103` 的 explainBody 承诺 4 种调度方式，实际 `STRATEGY_OPTIONS` 是 5 个，且"按性能分配"描述的其实是**权重**语义而 UI 无权重输入框（`weight` 恒为 1）。

> ✅ **零成本**：`PoolDefinition.name`（显示名）、`rateLimitPerMinute` / `hourly|daily|monthly Request|TokenLimit` / `quotaAlertThreshold` **全部已存在**。

---

### P0-G 全局配置 4 个 tab 标题人话化 + 说明

现状 tab 名：`记录治理` / `负载均衡` / `熔断阈值` / `用量配额` —— 四个都是内部术语。

**动作**：`日志记多细` / `流量怎么分` / `模型连续出错怎么办` / `额度怎么记`，每个 tab 加「影响什么 / 何时该改」两行小字，底部加「这些是默认值，单个模型要单独调请去 接入管理」。

---

### P0-H 员工侧错误提示分层扩到全部员工页

**全局性问题**：`utils/request.ts:83-91` 主动把后端原始 message 提升进 `error.message`，然后 `MyQuotaView.vue:256` / `MyKeysView.vue:42` / `MyBillsView.vue:95,98` 裸透传 → **后端技术报错原样弹给员工**（"instance not reachable"、"no available provider"）。而 `playground/utils/errorHandler.ts` 已按 `isStaff()` 做过分层，**只覆盖测试场**。

**动作**：新建 `utils/friendlyError.ts`，按角色分支改写；员工侧统一回「请联系管理员，并提供错误码 {code}」，管理员侧保留原始信息。

> ✅ 现成资产：`components/common/FailureExplainer.vue`（含 `explanation` + `goFix()` 去修复链接）已跑通"错误可自解释 + 给出路"模式，只是被关在 callHistory。**推广它，不要重新设计。**
> ✅ `error.serverErrorCode` 已在拦截器里透出，可支撑"给管理员看错误码、给员工看人话"。

---

### P0-I 页面目标说明：统一约定 + 覆盖率从 29% 提到 100%

现状：35 处 `PageHeader` 只有 10 处传了 `:subtitle`（**29%**），且说明文案有 **4 种平行实现**（`page-tip` / `page-heading__sub` / PageHeader prop / `menu.ts` 死数据）。无统一约定 → 必然"有的页面有、有的没有、风格不一"。

**动作**：统一到 `PageHeader` 的 subtitle；以 `menu.ts` 的 `subtitle` 为**单一文案源**（页面查 menu 配置取用）；侧边栏加 tooltip 渲染。

---

### P0-J 「申请新密钥」「续期」两个死按钮

`MyKeysView.vue:181`、`:248` 点了只弹 toast「功能尚未接入」，但按钮**渲染且可点**。违反"空态必有出口"的反面 —— 有入口没出口。

**动作**：接申请流程（需后端）或降级为纯文案引导（"请联系管理员为你创建"）。

---

### P0-K 路由 `meta.permissions` 与菜单 `permission` 统一为单一来源

**已修正的误判**：先前认为"5 条路由未声明 meta → 敲 URL 可绕过角色可见性"**不成立**——那 5 条都在菜单里，守卫 L278-285 会用菜单配置兜底拦截。真正无前端拦截的只有 `/guide`、`/docs`、`/settings`、404，均属合理。

**真实价值**：消除双份声明的漂移风险。**验收应为"一致性巡检 100% 通过"**，而非"敲 URL 被重定向"（后者本来就通过，无法证伪）。

---

### P0-L【已坐实·安全】后端权限确实没挡住：员工可直读全量资源池与全公司调用明细

**已实测坐实**（架构师报告 §3.5.3 / §3.5.4，主理人复核代码位置）：

| 端点 | 规则 | 员工（USER 角色）实际权限 | 泄漏内容 |
|---|---|---|---|
| `GET /api/config/pools/list` | `PermissionRuleRegistry.java:124` → `CONFIG_POOLS_READ` | **`CONFIG_POOLS_READ` 明确授予 USER 角色**（`RolePermissionSeeder.java:223`） | **全公司虚拟模型名 + 全部成员 `instanceId` + `weight`**（`PoolDefinition.members` / `PoolMember.instanceId`） |
| `GET /api/token-usage/recent?limit=100` | GET → `TOKEN_USAGE_READ` | **同样在 `userCodes()` 中授予 USER**（`RolePermissionSeeder.java:235`） | **全公司最近 100 条调用的调用者 userId、apiKeyId、客户端 IP、上游实例地址、失败原始错误信息** —— 且返回的是 JPA 实体全字段（`TokenUsageController.java:144`） |

**为何这是"设计疏漏"而非"刻意设计"**：同一个仓库里 `BillingController.java:124-127` 的 `/api/billing/team` 与 `:152-154` 的 `/api/billing/departments` **都明确写了** `@PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")`。团队级数据在 billing 侧做了隔离，在 token-usage 侧漏了。

**根因（产品层）**：员工侧「我的密钥」要算**混合单价**（`MyKeysView.vue:82-93` 调 `getPoolList()` 拉全量池 → `:104-116` 用 `key.allowedGroups` 过滤）。**员工为了算一个数字，被迫拉取全量管理员级数据。**

**修复方向（成本极低）**：
1. 混合单价**改服务端算好返回** —— 在 `GET /api/auth/api-keys/mine` 的返回里带上该密钥的加权均价（`ApiKeyManagementController.java:88-104` 已在做归属解析，**加一个字段的成本很低**），前端只渲染数字 → 员工自然不再需要 `getPoolList()`
2. `TokenUsageController` 两个 GET 端点加 `@PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")`，**与 billing 侧对齐**
3. 若需保留员工自助读用量：新增 `GET /api/token-usage/mine`，归属由服务端从 principal 解析（**照抄 `PermissionRuleRegistry.java:81` 的 `/api/call-history/mine` 模式** —— 该处已有「必须登记在通配规则之前」的注释与实现）
4. 返回 DTO 而非 JPA 实体，避免 `clientIp` / `instanceUrl` / `errorMessage` 顺带泄漏

> ⚠️ **写操作侧已核实无误**：`/api/config/pools/**` 与 `/api/token-usage/**` 的 **写** 权限绑定 `config:pools:write` / `token-usage:write`，**员工无写权限，无需处理**（架构师报告 §3.5.5，防止误判扩大范围）。
> **验收**：用 USER 角色真实 JWT 直调上述两个端点，断言 **403**。这是**唯一可用"证伪"方式验证**的 P0 —— 前端菜单隐藏不构成安全证据。

---

## 三、P1 / P2 概要

> 本章为**产品级**改进项（产品经理 v2 方案 32×P1 + 18×P2）。代码级的 6×P1 + 8×P2 缺陷见[勘察报告 §3.2/§3.3](./non-technical-usability-code-audit.md)，已在下方相关条目中标注。

### 3.1 三条"能力回退"与"已建成未暴露"的正面发现（产品经理 v2，主理人已实测复核）

这三条改变了实施策略 —— **不是新建，而是把已做好的东西接回去**：

| # | 发现 | 实测证据 | 动作 |
|---|---|---|---|
| **①** | **三态「下一步」引导已存在但在 B 套** | `views/models/ProviderManagement.vue:105-107` 有 `nextStepHint(p)` 三态（没 Key→去设 Key / 没模型→去加模型 / 有模型→去创建密钥），`:118-147` 有空态 onboarding 块（`provider.onboarding.*` 17 条 i18n 齐全）。而**菜单入口 A 套的引导卡是静态的** | 结论修正：**不是"产品没做过下一步引导"，是重构时能力回退**。移植回 A 套并扩到 4 态（加"有无配价""有无虚拟模型"），**零新增成本** |
| **②** | **完整的管理员四步向导已建成，且唯独缺配价** | `/quick-start` 路由真实存在（`router/index.ts:72-75`，权限 `config:instances:read`）→ `views/tools/QuickStartWizard.vue`（1080 行），`STEPS = ['provider','key','import','done']`：选服务商 → 填上游密钥 → 发现并导入模型（自动按模型 ID 识别分类）→ 自动创建默认分组 + 测试密钥 + 复制 curl/Python SDK。**grep `price\|配价\|价格` 零命中** → 四步唯独没有配价 | **不需要新建管理员向导**，需要把它接进菜单 + **补第 5 步「配价」** —— 而这正是账单 ¥0 的根源，向导是这条断点的最佳收口位置 |
| **③** | **`OnboardingSteps.vue` 是可复用的样式组件，不是复活对象** | `views/_attic/OnboardingSteps.vue`（160 行）是**步骤条组件**（✓已完成/进行中/待办 三态 + 可点击跳转）—— 正是待办卡需要的形态。但它的 4 步（选协议适配→建模型分类→接入模型→测试连通）是 **v3.5 前的信息架构**，前两步已下沉到 admin 专属页，普通 ops 进去撞 403，第 3 步还指向要下线的 `/models` | **模式复用、内容不复用**：把三态样式搬到新组件，组件随 `_attic` 删除（理由写明，防后人误以为漏做） |

**另两条产品经理 v2 的重要修正**：
- **「对外名字」→「员工用的模型名」**：配合一句固定解释「这就是员工在 AI 工具里填的名字。填 company-chat，员工就填 company-chat —— 他不需要知道背后是 GPT 还是 Claude」。这一句同时解决"参照系不明 / 员工该填什么 / 为何看不到厂商模型"三件事
- **异常管理 1330 行并入「调用观测」作第二 tab**（不新增菜单项）：「谁在什么时候调了什么」+「哪些没调成、为什么」本是同一件事两面；**不并入「状态监控」**（那是网关自身健康，异常是业务调用失败，视角不同）

**P1（32 条）** 分 6 类：
- **引导与进度（6）**：接入管理待办卡（移植 `nextStepHint` 三态并扩到 4 态）、虚拟模型待办卡、`SetupProgress.vue` 跨页共用组件、ops 首引导 3 屏、**「快速接入」接进菜单 + 补配价步**、三段「里程碑+出口」文案
- **表单简化（4）**：虚拟模型新建改 3 步向导、配额 4 档预设（不限/轻度/标准/严格 → 落既有字段，零 DDL）、创建密钥表单重排 3 分区、权限门控折叠标题改写
- **术语与解释（5）**：扩展现成 `constants/glossary.ts`（**实测 10 个 entry**，`useGlossary().term()`；已有现成 `components/common/TermHelp.vue` 问号组件）覆盖 ops 侧、路由规则条件/动作列自然语言 tooltip、策略中心定位说明卡、**`failureExplain` 第三张双口径表**
- **员工侧（5）**：快速上手双分支 + 验证步骤、我的额度加「我能用的模型」区块、我的账单加行合计、我的密钥白名单显示友好名、**员工侧可见范围收窄（只显示白名单内模型）**
- **运维侧（4）**：团队用量加「按虚拟模型」维度 + 逐项未配价标记、调用观测加快捷筛选 + 异常 tab、技术总览加行动卡区、链路追踪降级为高级工具
- **列表与状态（4）**：全部密钥统计卡改任务流 + 「只看需要处理的」筛选 + 创建成功弹窗加「接下来怎么做」、命令面板支持任务词别名（`menu.ts` 加 `aliases`，搜"接厂商""发密钥"能命中）

**P2（18 条）**：复制前缀、导出、CSV、筛选分享、问候语、截图展开、部门批量发密钥、模板复制、回滚提示、虚拟模型复制、折叠说明、环比等。

### 3.2 五个核心页的页面级布局

详见[产品方案 §3.5](./non-technical-usability-plan.md)（含 Tab3 熔断限流线框图）。核心结构：

| 页面 | 区块顺序 |
|---|---|
| **接入管理** | 页头+副标题 → **待办卡（4 步状态驱动，完成打勾，未完成给行动按钮）** → 业务规则告警（保留"员工不能直接调用刚接进来的厂商模型"这句）→ 3 tab → 模型抽屉加保护状态摘要 |
| **虚拟模型** | 页头+副标题 → 解释卡 → 待办卡 → 列表（+3 列）→ 成员 chip（+价格标签）→ **新建改 3 步向导**（命名+说明 / 选成员（空时给"先去接入厂商"出口）/ 策略+配额 4 档预设） |
| **全部密钥** | 页头+副标题 → **待处理区（4 张卡从"数字"变"任务"，标红的那些需要你处理）** → 筛选条（+只看需要处理的）→ 表格（列头显示 ¥ 与单位双口径）→ 创建表单 3 分区（给谁/能用什么/花多少）→ 成功弹窗「接下来怎么做」三选一 → 底部"想看你自己的密钥？→ 我的密钥" |
| **策略中心** | 页头+副标题 → **定位说明卡（正例 + 反例说清边界）** → 规则列表（条件/动作列加自然语言 tooltip）→ 空态"大多数情况你不需要它"+ 按钮 |
| **全局配置** | 页头+副标题「只有这一页的设置对所有模型生效；要单独调某个模型，去 接入管理」→ 4 个 tab 人话名 → 每个 tab「影响什么/何时该改」→ 底部"这些是默认值" |

---

## 四、术语体系

**四档分层**（产品经理方案 §4.1）：

| 档 | 判定 | 处理 | 例 |
|---|---|---|---|
| L1 必须人话化 | 员工日常读到、且不影响正确使用 | 直接换词 | Token 用量→已用量；白名单→可用范围 |
| L2 人话化+解释 | 员工偶尔读到、不懂会做错事 | 换词 + 首次出现加小字 | 「额度上限（这张卡最多能用的量）」 |
| L3 保留技术词+tooltip | 员工不该遇到，ops 需要精确度 | 保留 + 解释 | 熔断阈值（tooltip：连续失败几次就临时踢出轮询） |
| L4 不管 | 纯内部实现细节 | 原样保留 | Base URL（ops 侧必填，加 tooltip） |

**关键结论：不新建 `opsTerms.ts`** —— 现成 `frontend/src/constants/glossary.ts` 已有 `useGlossary().term(key)` 机制，**实测 10 个 entry**（`slowQuery` / `minCount` / `samplingRate` / `span` / `circuitBreaker` / `halfOpen` / `hashAlgorithm` / `virtualNodes` / `weight` / `tokenQuota`），配套的 `components/common/TermHelp.vue`（问号图标，`LoadBalancerGlobalConfig.vue:58` 已在用）也已就绪。**扩展它**，避免两套并行。
> 架构师报告称"只覆盖 8 个词"、主理人一度误算为 32 条 —— **实测为 10 个 entry**（此前计数把每条 entry 的 `zh`/`en` 值行也计入，架构师的 8 更接近实际）。

**建议扩充的技术黑名单**（架构师实测，均有违规点）：
`trunkline.quota.enabled` 等配置键（`apiKeys.json` multiWindowHint）· `serviceType` 原始枚举（`PricingQuotaPanel.vue:34`、`PoolManagement.vue:535` 两处漏出，而同项目 `constants/serviceTypes.ts` 已有 `getServiceTypeLabel()`）· `CIDR`/`正则`/`Header`（`rule.json`）· `跨度 span`/`采样率`（`glossary.ts:25-31`）· `脱敏串`（`MyQuotaView.vue:274`）· `¥ 上限`（系统无金额预算字段）

---

## 五、可长期保持的工程措施

### 术语黑名单自动化巡检

当前 `package.json` 只有 `lint` / `type-check` / `test:api`（仅覆盖 `src/api`）→ **前端几乎无测试守护**。而项目已反复出现"i18n 动态拼键拼错 → key 不存在 → **静默回退显示原始 key**"的缺陷（`vue-tsc` 查不出、`eslint` 查不出、运行时不报错）。

**建议两个检查**：
1. zh-CN / en-US key 集合完全一致
2. 禁用词巡检 —— **扫描口径是关键**：只扫 i18n JSON 的值 + 模板静态文本节点，**排除注释与变量名**（实测 `MyQuotaView.vue` 注释+变量名里 40 处 token，但模板标签全部正确）。
   > 更稳的做法是维护**"允许术语"白名单**（每词标注允许出现在哪些页面），而非黑名单 → 误报率更低。

### 权限声明一致性巡检
菜单 `permission` ↔ 路由 `meta.permissions` ↔ 后端 `PermissionRuleRegistry` 三处必须一致，可做成静态检查。

---

## 六、上线批次建议

> 批次已按产品经理 v2 的"会骗人优先"原则重排 —— **第 0 批先解决会让人基于假数据做决策的东西**。

| 批次 | 内容 | 工作量 | 风险 |
|---|---|---|---|
| **第 0 批**（纯前端·止血）✅ 已交付 | **P0-0 下线 mock 页**（先做 Q12 的 InstanceManagement 挂载）+ **P0-E 策略中心副标题** ×2 + **P0-G 全局配置 tab 人话化** | 0.5 天 | 极低。**消除"会骗人的页面"与"菜单承诺 4 件只做 1 件"——这两条最伤信任** |
| **第 1 批**（纯前端）✅ 已交付 | **「价格与额度」+「已接入模型」合并为「模型配置」**（含 P0-C 熔断/限流独立成列并前移、P0-D 计价开关与未配价标记）+ **P0-A2 FailureExplainer 落点修正** + **P0-F 虚拟模型 3 列** | 1 天 | 低。**消除"同一个模型出现在两张表"与"熔断限流看不见"**；数据全部现成，零后端改动 |
| **第 2 批**（纯前端）✅ 已交付 | **P0-B 撞墙 403 页** + **P0-K 权限单一来源** + **P0-H 员工错误分层** + **P0-J 死按钮** + **P0-I 页面说明统一** | 2 天 | 低。消除"以为系统坏了"和"员工看到英文报错" |
| **第 3 批**（纯前端）✅ 已交付 | P0-A 方案 B（UI 显式禁用月额类字段）+ P1 员工侧 5 条 + 「对外名字」→「员工用的模型名」 | 1 天 | 低。员工体验闭环 |
| **第 4 批**（前后端）✅ 已交付 | **P0-L 越权泄漏修复**（4 条泄漏路径全部封堵：上游 API Key 凭据泄漏 / 全量定价 / 全量资源池 / 全公司调用明细）+ **P0-A 方案 A（补月度校验）** + P1「按虚拟模型」维度 + 员工侧可见范围收窄 | 1 迭代 | 中。**本批是安全批，不是体验批** |
| **第 5 批**（引导接线） | **移植 `nextStepHint` 三态 → 4 态** + **`/quick-start` 接进菜单 + 补第 5 步「配价」** + `SetupProgress.vue` + ops 首引导 | 1 迭代 | 低。**能力已在代码里，主要是接线**；价值高但依赖前 4 批 |

> **第 5 批是本方案性价比最高的一批** —— 三态引导、四步向导、步骤条组件**都已在代码里**，不需要新建，只需要把它们接回去并补上"配价"这一步。

> ⚠️ **例外建议：P0-L 建议从第 4 批提前**（第 3 批已顺带修掉其中「员工自助端点 403」的部分，但**调用明细里的 clientIp / instanceUrl / errorMessage 泄漏仍未修**）。它与其它 P0 不同 —— **不是"体验不好"，是已坐实的越权泄漏**（员工能读到全公司调用明细里的 clientIp / instanceUrl / errorMessage）。修复动作里「混合单价改服务端算好返回」成本极低（`ApiKeyManagementController.java:88-104` 已在做归属解析，加一个字段即可），可独立于批次提前实施。

---

## 七、可验证指标

| 指标 | 当前 | 目标 | 验证方式 |
|---|---|---|---|
| 接入厂商 → 建出可用虚拟模型耗时 | 无基线 | ≤ 15 分钟（含配价） | 新环境实测计时 |
| **配了月额度的密钥实际拦截率** | **0%（不生效）** | **100%** | 构造超限调用，断言 429 |
| **会骗人的页面数（改价不写库 / 假数据）** | **1（ModelPricing）** | **= 0** | 全站扫 `保存`/`确定` 按钮是否触发 API |
| **失效的「去修复」链接数** | **6 处（3 组目标失效）** | **= 0** | 遍历 `FailureExplainer` 全部 route，点一遍验证落地 |
| 员工页技术术语暴露数 | 18 项黑名单为基准；**错误提示路径未覆盖** | = 0（含错误提示） | 扩展巡检覆盖 `request.ts` 提升的 message |
| 熔断/限流入口可发现性 | 藏在价格表第 7/8 列 | 独立 tab，≤ 2 次点击 | 5 人可用性测试，≥ 4 人独立完成 |
| 账单 ¥0 未配价归因率 | 未采集 | 100% 可定位到具体模型 | 构造"3 成员仅 1 个配价" |
| 策略中心菜单承诺与实现一致率 | **4 项承诺 / 1 项实现** | 100% | 逐词对照 TABS 数组 |
| 同一功能可达入口数 | **接入管理 2 套** | = 1 | 路由表去重 |
| 死按钮数（员工可见页） | 2 | = 0 | 遍历所有 `el-button` 验证有响应 |
| 权限撞墙反馈 | **静默跳员工首页** | 5 秒内知缺哪个权限、找谁开 | 越权访问 UX 测试 |
| 「下一步」引导覆盖率 | A 套静态 / B 套三态（要下线） | A 套 4 态 | 每完成一步，页面出现对应出口按钮 |

---

## 八、需业务方拍板的事项

| # | 议题 | 选项 | 决策方 |
|---|---|---|---|
| 1 | **月度额度是否默认生效？** | A 补认证链路 / B UI 显式禁用 / C 账本默认开启 | 产品 + 后端 |
| 2 | 负载均衡最终形态 | 池策略唯一化（保留机制降级为内部兜底） | 架构（`plan-c-*.md` 已建议，待拍板） |
| 3 | 「策略中心」保留还是并入「全局配置」？ | 保留 1 tab / 并入（策略组清空） | 产品 |
| **4** | **「已接入模型」高级页（InstanceManagement 1296 行）怎么处置？** | **并入 A 套抽屉内 admin 可见链接（推荐）** / 独立页 / 删 | 产品（**P0-0 的前置依赖，必拍**） |
| **5** | **「快速接入」要不要占菜单项？** | **不占，做成接入管理页顶部主按钮（推荐，菜单不变长）** / 占一项（18→19） | 产品 |
| **6** | **异常管理放哪？** | **并入「调用观测」作第二 tab（推荐）** / 独立菜单项 / 不复活 | 产品 |
| **7** | **无权菜单隐藏还是置灰？** | **混合（隐藏为主 + 跨角色高频页置灰，推荐）** / 全隐藏 / 全置灰 | 产品 |
| **8** | **死代码删还是留？** | **删（推荐，留着会误导后人和 AI 助手以为"异常管理已上线"）** / 留 | 产品 + 架构 |
| 9 | 「我的额度」上限口径 | 前端估算 ¥ / 增加显式 `monthlyAmountLimit` 字段 | 产品 + 后端 |
| 10 | 权限拦截的 UX 形态 | 403 页面（推荐）/ toast | 产品 |
| 11 | 全局负载均衡卡是否变可写 | 加 PUT 端点 / 标注"只读·由虚拟模型页接管" | 产品 + 后端 |
| 12 | 「虚拟模型」还是「模型分组」是正式对外名？ | 同一对象现在 3 个名字（建议「虚拟模型」，与菜单一致） | 产品 |
| 13 | 接入管理拆页粒度 | 3 tab（推荐）/ 3 页 / 保留 1 表分列区 | 产品 |
| 14 | 员工侧「申请额度/申请密钥/续期」接工单还是下线 | 接 / 下线 | 产品 |
| **15** | **员工混合单价改服务端算好返回，是否同时下线 `GET /api/config/pools/list` 对 USER 角色的授权？** | **完全下线（推荐，服务端返回均价后前端不再需要）** / 保留但改返回 DTO 不含 instanceId | 产品 + 后端（**P0-L 修复动作，须拍**） |
| **16** | **员工自助读用量是否需要保留？** | **新增 `/api/token-usage/mine`（推荐）** / 完全禁止员工读明细 | 产品 + 后端（**P0-L 修复动作，须拍**） |

---

## 附录：实施进度记录

> 记录每次落地批次的结果。**已交付** = 代码已合入工作区且通过三重验证（`vue-tsc --noEmit` / `vite build` / Playwright E2E）。

### 第 0 批（纯前端·止血）—— ✅ 已交付 2026-11-02

| 项 | 状态 | 落地位置 | 说明 |
|---|---|---|---|
| **前置**：`InstanceManagement` 挂到 A 套 | ✅ | 新建 `frontend/src/views/providers/InstanceManagementPanel.vue` | 薄包装组件在**包裹层** `provide('skeletonEmbedded', true)`，不吞掉 A 套自己的页头 |
| **P0-0** 下线「价格与额度」mock 页 | ✅ | 移出 3 个文件 → `frontend/src/views/_attic/p0-0-retired-models/README.md` | `ModelPricing.vue`(95 行纯 mock) / `ModelsView.vue`(B 套壳层) / `ProviderManagement.vue`(1475 行)。路由 `/models` 改为重定向到 `/providers?tab=...` 承接历史链接 |
| **P0-E** 策略中心副标题改写 | ✅ | `frontend/src/config/menu.ts` + `frontend/src/layouts/ConsoleLayout.vue` + `locales/*/shell.json` | 副标题 `'帮你省钱、分流、保稳定…'` → `'按条件把请求转给不同的模型'`；**并修复 `menu.ts` 的 `subtitle` 从未被渲染的缺陷**（侧边栏改 `el-tooltip` 承载） |
| **P0-G** 全局配置 4 tab 人话化 | ✅ | `frontend/src/views/settings/GlobalSettingsView.vue` + 新建 `locales/*/globalSettings.json` | tab 标签改为「日志记多细 / 流量怎么分 / 模型连续出错怎么办 / 额度怎么记」；每 tab 补「影响什么 / 何时该改」；底部加默认值出口说明。**`name` 值不变（record/lb/circuit/quota），深链不受影响** |

**顺带修复的死链（10 处）**：

| 位置 | 原链接 | 现链接 |
|---|---|---|
| `FailureExplainer.vue` × 6 组 | `/strategy?tab=circuit`、`/observe?tab=rate`、`/keys`、`/models` | `/settings/global?tab=circuit`、`/all-keys`、`/providers?tab=instances` |
| `workbench/Overview.vue` × 5 | `/models`、`/keys` | `/providers?tab=instances` 等 |
| `playground/PlaygroundModelSelect.vue`、`chat/ChatContainer.vue`、`circuit-breaker/Monitoring.vue`、`tools/QuickStartWizard.vue`、`_attic/OnboardingSteps.vue` | `/models` | `/providers?tab=instances` 等 |

**能力不丢的证明**（B 套能力迁移对照）：

| B 套原有能力 | 去向 |
|---|---|
| `ProviderManagement.vue` 三态引导（`nextStepHint`/`nextStepActionLabel`/`goNextStep`） | 移植到 A 套供应商表格新增的**「下一步」列**（min-width 220，带行动按钮） |
| `InstanceManagement`（实例管理、熔断/限流/配额的厂商模型级配置） | 成为 A 套第 3 个 tab **「已接入模型」** |
| `ModelPricing` 的价格表 | 走真实端点 `/api/config/prices/catalog`（在「价格与额度」tab） |

**验证结果**：

```
vue-tsc --noEmit              → EXIT=0，零类型错误
vite build（输出到 TEMP）      → EXIT=0，built in 7.85s
Playwright E2E                → 18/18 PASS，浏览器 console 无错误
  ① /models 重定向 /providers?tab=instances   ① mock 文案「改完立即生效」已消失
  ① mock 假虚拟模型名已消失                     ② A 套 3 tab：接入厂商|价格与额度|已接入模型
  ③ 三态引导文案「下一步：创建网关密钥，开始调用」 ④ 策略中心副标题已改写
  ⑤ 4 tab 已人话化                             ⑤ 「影响什么/何时该改」8 行
  ⑤ 底部默认值出口                             ⑥ 侧边栏 tooltip 显示 subtitle
  ⑦ 页面内 0 个 /models 死链
构建产物复核：无 ModelPricing/ModelsView 产物；ProviderConsoleView-*.js 含「已接入模型」；
             i18n-*.js 含 4 个人话化 tab 文案；GlobalSettingsView-*.js 含 12 个 globalSettings.* key
```

**本批未做（留给后续批次）**：

| 遗留 | 归属 |
|---|---|
| `FailureExplainer` 的**文案**仍是技术黑话（"实例""后端"），只修了链接 | P0-A2 文案双口径化 → **第 1 批已解决** |
| 页头 `PageHeader` 的 subtitle 仍来自各页自己的 locale，与 `menu.ts` 未统一 | P0-I → **第 2 批已解决**（`menuSubtitle(path)` 单一文案源） |
| 「价格与额度」tab 名称仍偏技术，且未配价无标记 | P0-D → **第 1 批已解决**（并入「模型配置」） |
| 熔断/限流仍藏在价格表第 7/8 列 | P0-C → **第 1 批已解决**（独立成列并前移到状态列旁） |

### 第 1 批（接入管理·两表合并）—— ✅ 已交付 2026-11-02

| 项 | 状态 | 落地位置 | 说明 |
|---|---|---|---|
| **P0-C + P0-D** 「价格与额度」+「已接入模型」合并为「模型配置」 | ✅ | 新建 `frontend/src/views/providers/ModelConfigPanel.vue` | 一行 = **一个已接入模型实例**，跨模型分类平铺；模型信息 / 单价 / 额度 / 计价 / 熔断 / 限流 全并到一行 |
| 接入管理 tab 收敛 3 → 2 | ✅ | `frontend/src/views/providers/ProviderConsoleView.vue` | 删掉「价格与额度」「已接入模型」两个 tab，只留「接入厂商 / 模型配置」 |
| 旧深链兼容 | ✅ | `ProviderConsoleView.vue` + `router/index.ts` | `LEGACY_TAB_ALIAS`（pricing / instances → models）+ `onMounted` URL 规范化；全站 13 处跳转改指 `?tab=models` |
| 三个被取代的组件归档 | ✅ | `_attic/merged-model-config/README.md` | `PricingQuotaPanel.vue`(648 行) / `InstanceManagementPanel.vue`(24 行) / `InstanceManagement.vue`(1296 行) 移出工作区 |
| 新增中英双语词条 | ✅ | `locales/zh-CN/modelConfig.json` / `locales/en-US/modelConfig.json` | 顶层单命名空间 `modelConfig`，各 48 键 |

**为什么必须合并（不是简单删一个 tab）**：

用户最初判断「已接入模型」与「价格与额度」功能重复、可直接删除。核实后发现：

- 熔断 / 限流 4 项**确实完全重复**（同一套实例级 API、同一个组件）。
- 但 `InstanceManagement.vue` 另有 **7 项全站唯一入口**的能力：
  实例编辑（权重 / 适配器 / 接入地址）、单条删除、批量启停、批量删除、
  CSV 导出、搜索 / 状态筛选 / 分类切换 / 分页、未入组标记。
- 直接删 tab 会让管理员**再也调不了权重、改不了接入地址、删不掉单个模型**。

因此改为「合并成一张表」，两张表的能力**一项不裁**。

**合并前的两处口径冲突（本批修正）**：

| 项 | 原「价格与额度」表的口径 | 合并后 |
|---|---|---|
| 行粒度 | 按 `model_name` 去重，同名实例跨供应商合并成一行 | 按**实例主键**一行 |
| 熔断 / 限流开关状态 | 取价格目录里的**同名代表实例**（`instanceIdByModel.putIfAbsent` 首个命中）——同名多实例时是错的 | 直接用**实例 DTO 自带**的 `circuitBreaker` / `rateLimit`（`convertToDTO` 已装配） |
| 单价 join | 同上 | 按**实例名** join `model_price` —— 与 `BillingService` 按 `token_usage.instance_name` 计价口径一致 |

**合并后的 9 列**：

| # | 列 | 内容 |
|---|---|---|
| 1 | 选择框 | 仅 `canWrite` 出现，驱动批量启停 / 删除 |
| 2 | 模型名 | 备注名 + 真实模型名 + 模型分类小标签 + 未入组标记 |
| 3 | 上游厂商 / 接入地址 | 厂商名 + 「来自供应商 X」+ 域名 + 路径 |
| 4 | 状态 | 启用 / 停用 + 熔断运行态 + 健康点 |
| 5 | 熔断 / 限流 | 两个实例级开关竖排 + 各自配置入口 |
| 6 | 单价 | 「输入」「输出」竖排，**改完即存** |
| 7 | 计价 | 开关（关闭 → 该模型不参与账单计价，¥0） |
| 8 | 额度与限速 | 摘要 chips + 「配置」入口 |
| 9 | 操作 | 编辑实例 / 删除实例（fixed right） |

**列结构二次调整（12 列 → 9 列）**：

第一版合并表 12 列、总宽 1806px。用 Playwright 量 `scrollWidth / clientWidth` 发现：
1280 视口下表格可视宽仅 963px，而右侧「操作」列 `fixed="right"` 会盖住右边 210px
—— **熔断、限流两列落在被遮挡区，首屏根本看不见**。于是：

- 「模型分类」独立列 → 并入「模型名」列的小标签
- 「熔断」「限流」两列 → 合并成一列，**前移到状态列之后**
- 「输入单价」「输出单价」两列 → 合并成一列「单价」
- 「保存价格」按钮 → 改为输入框 `@change` 自动保存（少一个要找的按钮）
- 「额度配置」入口 → 从操作列移到「额度与限速」列内

结果：12 列 1806px → 9 列 1483px，**1800 视口下已无横向滚动条**。

**验证结果**：

```
vue-tsc --noEmit              → EXIT=0，零类型错误
vite build                    → EXIT=0，built in 8.74s
Playwright E2E（主脚本）      → 31/31 PASS，浏览器 console 无错误
Playwright E2E（三角色矩阵）  → 15/15 PASS
  ① tab 数 = 2（接入厂商 | 模型配置），「价格与额度」「已接入模型」已下线
  ② 表格行数 = 后端实例数（3 == 3），模型名全部出现
  ③ 单价按实例名 join —— 逐行与后端 `model_price` 比对，全部一致
  ④ 额度摘要 chips 正确（限速 3 次/分钟 / 每小时 10 次 / 每小时 3 Token）
  ⑤ 熔断 / 限流开关状态 = 实例自带配置（不是价格目录的代表实例口径）
  ⑥b 列数 = 9，单价竖排 2 行，分类并入模型名列
  ⑥c 单价改完自动保存 —— 0.001 → 0.002 已落库，并已复原为 0.001
  ⑦ 搜索过滤命中 1 行      ⑧ 分类下拉 7 项齐全
  ⑨ 旧深链 ?tab=pricing / ?tab=instances 均落到「模型配置」且 URL 被规范化为 ?tab=models
  ⑩ /models?tab=pricing → /providers?tab=models
  ⑪ 概览页无旧 tab 链接
  三角色：admin / OPERATOR 可见可写；USER 被友好引导到「我的额度」，不白屏
```

#### 第 1 批·续（P0-A2 剩余 + P0-F）—— ✅ 已交付 2026-11-02

> ⚠️ 上一条「✅ 已交付」的标记**下早了**：当时只交付了「两表合并」这一项，
> 规划表里第 1 批的另外两项（P0-A2 FailureExplainer 修正、P0-F 虚拟模型三列）
> **当时并未做完**。本节补齐这两项。

---

#### P0-A2 剩余：命名空间提顶层 + 文案双口径

| 项 | 状态 | 落地位置 | 说明 |
|---|---|---|---|
| 命名空间提顶层 | ✅ | 新建 `locales/{zh-CN,en-US}/failureExplain.json` | 从 `callHistory.failureExplain.*` 迁到顶层 `failureExplain.*`（组件从「某页子组件」升为「全站标准件」，命名空间是组件归属的镜像） |
| 旧节点清理 | ✅ | `locales/*/callHistory.json` | 原 `failureExplain` 节点已摘除，避免两处重复定义 |
| 引用同步 | ✅ | `components/common/FailureExplainer.vue` / `views/callHistory/CallHistoryList.vue` | 组件内 2 处 + 详情标题 1 处改指 `failureExplain.*`；全仓已无 `callHistory.failureExplain` 残留 |
| 文案双口径 | ✅ | `FailureExplainer.vue` + `failureExplain.json` | 运维侧（ops/admin）保留技术词；员工侧（user/viewer）走人话 |

**双口径取词结构**（不把字符串塞成对象，保持 vue-i18n 的扁平语义）：

```
failureExplain.<key>              → 运维侧文案（技术精确）
failureExplain.actions.<target>   → 运维侧按钮文案
failureExplain.staff.<key>        → 员工侧人话
failureExplain.staff.actions.<t>  → 员工侧按钮文案
```

取词逻辑用 `isStaff()`（`constants/employeeTerms.ts`）判断；员工侧文案缺失时
**回退到运维侧文案**（而不是显示原始 key —— i18n 动态拼键拼错会静默回退显 key，
这是项目反复踩过的坑）。

**员工侧改写示例**（避开 `STAFF_ONLY_TERMS` 黑名单）：

| 运维侧 | 员工侧 |
|---|---|
| 该实例已熔断，暂时被摘除以保护后端 | 这个模型暂时被停用，等它恢复后会自动重新接上 |
| 调用过于频繁，触发了限流保护 | 调用太频繁了，系统暂时挡了一下，稍等几秒再试 |
| 今天的 Token 额度已用完 | 今天的使用量已经用完了 |
| 去调限流 / 去看熔断 / 去查实例 | 去调整调用速度 / 去看保护设置 / 去查模型 |

> 「实例」「熔断」都在 `STAFF_ONLY_TERMS` 黑名单里，直接推给员工会违反员工侧术语验收。
> `Token` 也在黑名单（员工侧统一说「用量 / 单位」，见 `EMPLOYEE_TERMS.amountUnit`）。

---

#### P0-F：虚拟模型列表加「价格状态 / 配额 / 状态」三列 + 成员 chip 价格标签

| 项 | 状态 | 落地位置 | 说明 |
|---|---|---|---|
| 价格状态列 | ✅ | `views/config/pools/PoolManagement.vue` | 全配价 → `success`「已配价」；部分配 → `warning`「N/M 个已配价」+ 均价；全未配 → `danger`「未配价」；无成员 → `info` |
| 额度限制列 | ✅ | 同上 | 只列真正设了上限的维度（每分钟 / 每小时 / 每天 / 每月 / 每月 Token），全 0 → 显示「没设限制」 |
| 状态列 | ✅ | 同上 | `enabled` 独立成列（原来挤在名称列里的「已停用」tag 已移出） |
| 成员 chip 价格标签 | ✅ | 同上 + `pool.json` | 每个成员 chip 上挂价格小标签（复用创建清单已有的 `priceInfo` 分层逻辑），未配价的用红色警示 |
| 权重输入框 | ✅ | 同上 | 编辑弹窗每个成员一个「流量权重」输入框，仅「按权重随机」策略时显示 |
| 说明文案修正 | ✅ | `pool.json` 的 `explainBodyV2` | 原文承诺 4 种调度方式、实际 `STRATEGY_OPTIONS` 是 5 种 → 改为 5 种并与枚举逐条对齐 |
| 新增中英双语词条 | ✅ | `locales/{zh-CN,en-US}/pool.json` | 各 +26 键（zh 99→125 键） |

**为什么加权重输入框**：

`explainBody` 原文写「按性能分配（好机器多接活、差机器少接）」，但：

- 实际策略枚举是 `weighted-random`（按**权重**随机），语义不是「按性能」
- `PoolMember.weight` 字段**存在且被真正消费**（`PoolSelector.java:122` →
  `resolveCandidates` → 负载均衡器），但 UI **没有入口** → 权重恒为 1
  → 「按权重随机」实际等价于「随机」

所以这不是加装饰，而是**补上一个承诺过但拿不到手的控制点**。

**顺带修正的两处布局缺陷**（都是加权重框后才暴露的）：

1. **模型名被挤成 1 个字符**（`deepseek-v4.1-flash` → `d`）：
   `.model-order-desc` 是 `flex-shrink: 0`，`.model-order-name` 是唯一可压缩项。
2. **描述被压成竖排单字**（首次修复给了 desc `flex: 0 1 auto`，浏览器把它压到近 0 宽再逐字换行）。

最终改法：成员行改成**两段式**——上行「徽章 + 模型名 + 权重 + 操作按钮」，
下行描述独占整行。模型名与描述都不再抢宽度。

**验证结果**：

```
vue-tsc --noEmit              → EXIT=0，零类型错误
vite build                    → EXIT=0，built in 22.22s
Playwright E2E（主脚本）      → 21/21 PASS
  ① 虚拟模型列表表头 = 对外名字 | 背后挂的模型 | 策略 | 价格状态 | 额度限制 | 状态 | 本月调用 | 操作
  ② 三列全部渲染：已配价 ×3 / 没设限制 ×3 / 启用中 ×3
  ③ 成员 chip 价格标签 = 约 ¥0.001/千 Token ×2 + 约 ¥0.002/千 Token ×3
  ④ 表格无横向溢出（scrollWidth 1519 == clientWidth 1519）
  ⑤ 编辑弹窗权重输入框数量正确（单成员池 1 个 / 双成员池 2 个）
  ⑥ 权重真落库：后端 [[3],[1,1],[1,1]] → 断言 weight=3 存在 → 已复原 [[1],[1,1],[1,1]]
  ⑦ 员工页无禁用词、无 pageerror
Playwright E2E（FailureExplainer）→ 8/8 PASS
  ⑧ ADMIN 侧文案 =「调用过于频繁，触发了限流保护」/ 按钮「去调限流」→ 保留技术词
  ⑨ 「去修复」落点：/observability → /providers?tab=models，落点 tab 确实是「模型配置」（非白屏）
Playwright E2E（双口径）        → 13/13 PASS
  ⑩ USER 侧文案 =「调用太频繁了，系统暂时挡了一下，稍等几秒再试」/ 按钮「去调整调用速度」
  ⑪ USER 侧 11 个禁用词零命中；ADMIN 侧保留原技术词
```

**验证过程中踩到的三个坑（复用价值高）**：

| 坑 | 现象 | 正解 |
|---|---|---|
| **裸 fetch 不带鉴权头** | 页面内 `fetch('/api/config/pools/list')` 返回 401（`AUTH_MISSING`），而 axios 请求 200 | 认证头是 axios 拦截器加的（`Jairouter_Token`）；脚本里要用 `fetch` 就手动带 `localStorage.getItem('admin_token')` |
| **E2E token 存储键** | 注入 token 后仍停在登录页 | 键是 **`admin_token`**（`stores/user.ts` / `utils/request.ts`），不是 `token` / `access_token` |
| **mock 响应结构** | 页面显示「共 0 条记录」 | 分页响应用 `content` / `totalElements`（**不是** `items` / `total`），见 `api/callHistory.ts` |

**第 1 批整体状态**：三项子项现已全部交付 —— 两表合并（上一节）+ P0-A2 剩余（本节）+ P0-F（本节）。

**本批遗留**：

| 遗留 | 说明 |
|---|---|
| 组件内 `readonly-tip` 分支是防御性代码 | 当前 RBAC 下「能进这张表但不能写」的角色不存在（`menu.ts` 的 `/providers` 限定 `roles: ['ops','admin']`，两者都有 `config:instances:write`）。后端给 USER 发了 `config:instances:read` 但前端菜单层先拦下 —— 既有设计，非本次引入 |
| 「模型配置」没有批量改价 | 目前只能逐行改；批量改价建议放到第 2 批（先做「按分类批量设价」） |
| 单价自动保存无「撤销」 | 改错只能手改回来；如需撤销要引入操作历史，超出本批范围 |

## 附录：本次分析的证据来源

| 产出 | 作者 | 内容 |
|---|---|---|
| [代码现状勘察报告](./non-technical-usability-code-audit.md) | 高见远（架构师） | 603 行。四段业务流代码地图、18 菜单逐项诊断、一致性缺陷清单（4×P0 + 6×P1 + 8×P2）、术语对照表与黑名单、12 项可复用模式、15 项待明确事项 |
| [产品体验方案](./non-technical-usability-plan.md) | 许清楚（产品经理） | 1418 行（v3）。角色画像、旅程断点地图、分菜单改进清单（全部 18 项）、5 个核心页页面级布局、术语四档分层、19×P0 + 32×P1 + 18×P2、新手引导、i18n key 归属表、验收标准与批次 |
| 本汇编 | 齐活林（主理人） | 交叉校验两份产出，**修正 9 处前提错误**，新增 P0-0 / P0-A2 两条「会骗人」缺陷，坐实 P0-L 安全问题，核验全部技术依赖 |

**已修正的前提错误（记录以免复发）**：
1. 计价按虚拟模型名 → **实为按厂商模型名**（`BillingService.java:32-36`，`token_usage.instance_name`）
2. 熔断/限流只有全局级 → **实为厂商模型级已有**（`PricingQuotaPanel.vue:96-135`）
3. `ServiceManagement.vue` 31 个表单字段 → **实为 15 个**（grep 含闭合标签）
4. 快速上手 1080 行 → **实为 218 行**（1080 行的是 `tools/QuickStartWizard.vue`，管理员四步向导，**不在菜单**）
5. 5 条路由敲 URL 可绕过可见性 → **不成立**（菜单兜底已拦）；真正无拦截的只有 `/guide` `/docs` `/settings` /404
6. 菜单 16 项 → **实为 18 项**（团队组 8 项；`menu.ts` 文件头注释已过期）
7. `glossary.ts` 覆盖 8-10 词 → **实为 10 个 entry**（更值得扩展而非新建）
8. 「产品没做过下一步引导」 → **不成立**：`ProviderManagement.vue:105-147` 有三态 `nextStepHint` + onboarding 块，**是重构时能力回退**
9. 「B 套只是功能少」 → **实为纯 mock**：改价不写库却写着"改完立即生效"（比缺功能更糟）

---

## 附录：可视化图

| 图 | 文件 | 内容 |
|---|---|---|
| 业务流类图 | [`non-technical-usability-class-diagram.mermaid`](./non-technical-usability-class-diagram.mermaid) | 接入厂商 → 虚拟模型 → 密钥 → 员工调用 四段的类关系与配置落点 |
| 业务流时序图 | [`non-technical-usability-sequence-diagram.mermaid`](./non-technical-usability-sequence-diagram.mermaid) | 四段流程的调用时序，每处断点标注「哪一行代码 → 为什么错」 |
| 负载均衡 4 通道判定 | 勘察报告 §C.3 | 规则 LB_STRATEGY > 池策略 > per-serviceType（对池流量无效）> 全局兜底（不可达） |

### 第 2 批（撞墙与错误）—— ✅ 已交付 2026-11-02

| 项 | 状态 | 落地位置 | 说明 |
|---|---|---|---|
| **P0-B** 撞墙 403 页 | ✅ | 新建 `frontend/src/views/system/ForbiddenView.vue` + `router/index.ts` 守卫 | 无权访问不再静默跳「我的额度」，改跳 `/403?from=&missing=&role=`。页面含 4 项信息 + 一键复制申请文本 |
| **P0-B** 跨角色页置灰可见 | ✅ | `config/menu.ts`（`greyFor`）+ `composables/usePermission.ts` + `layouts/ConsoleLayout.vue` | ops 常看的「模型配置」「全局配置」灰显但可点，点击进 403 页说明缺什么权限 |
| **P0-K** 权限单一来源 | ✅ | `config/menu.ts` + `router/index.ts`（`applyMenuPermissions`） | 菜单 `permission` 成为唯一权威，路由手写的 16 处 `meta.permissions` 全删，改为建表后自动派生 |
| **P0-K** 一致性巡检 | ✅ | 新建 `frontend/src/utils/auditPermissionConsistency.ts` | 4 类检查（菜单↔路由路径 / permission 一致 / 同组件多路由一致 / 权限码拼写）。**验收标准 = 巡检 100% 通过** |
| **P0-H** 员工错误分层 | ✅ | 新建 `frontend/src/utils/friendlyError.ts` + `utils/request.ts` 拦截器 + 4 个员工页 | 员工侧按关键词→状态码→兜底改写成人话，**始终带错误码**；运维侧保留原始信息 |
| **P0-J** 死按钮消除 | ✅ | `views/my/MyKeysView.vue` + 新建 `frontend/src/utils/clipboard.ts` | 「申请新密钥」「续期」从「弹 toast 尚未接入」改为**真出口**：一键复制申请文本 |
| **P0-I** 页面说明单一来源 | ✅ | `config/menu.ts`（`menuSubtitle()`）+ 11 处页面 | 菜单 subtitle 是唯一文案源；19 条 subtitle 按 §3.9.2 去黑话 |

#### 实施中纠正的三个问题

**① P0-K 的真实漂移比方案记录的更多。** 方案只写「消除双份声明的漂移风险」，实测发现 **6 处不一致**：

| 路径 | 菜单 permission | 路由 meta.permissions | 结论 |
|---|---|---|---|
| `/all-keys` | **无** | 无 | ⚠️ **真的没保护** —— 「管理员视角的所有员工密钥」页，任何登录用户可直达。同一页面的 `/keys` 路由却声明了 `security:apikeys:manage`，两条路径权限不一致 |
| `/observability` | monitoring:tokenusage:read | 无 | 路由未声明 |
| `/providers` | config:instances:read | 无 | 路由未声明 |
| `/team` | monitoring:tokenusage:read | 无 | 路由未声明 |
| `/quickstart` | ai:playground:use | 无 | 路由未声明 |
| `/model-pools` | config:instances:read | config:instances:read | ✅ 一致 |

此前守卫还能拦住这些路由，靠的是「菜单命中优先于 meta」这个**巧合**，不是设计。修法不是补齐手写声明（那还是两份），而是**让菜单成为唯一权威**：路由表里手写的 `meta.permissions` 全部删除，改为建表后按绝对路径反查菜单自动注入。菜单加了新权限码，路由自动跟着变。

**② 员工错误有两条路径，只堵一条等于没做。**

| 路径 | 形态 | 拦截器能管吗 |
|---|---|---|
| 异常路径 | HTTP 4xx/5xx → axios reject → `catch (error)` | ✅ 在 `request.ts` 改写 `error.message`，**所有裸透传点自动受益** |
| 业务失败路径 | HTTP 200 但 `success:false` → 后端 message 在 `res.data.message` | ❌ **完全不经过 axios 错误分支**，必须调用点显式处理 |

`MyBillsView` 正是后者。只做拦截器改造的话，员工的账单页仍会看到原始报错。

**③ 死按钮不能删，只能给真出口。** `MyKeysView` 的「申请新密钥」「续期」是员工唯一的申请路径 —— 删掉就违反「空态必有出口」。改为：填用途 → **一键复制现成的申请文本** → 粘贴给管理员。后端申请流程仍未接入，但这是当下就能用的真路径，比一个弹完就没的 toast 强。

> 配套抽了 `utils/clipboard.ts`：企业内网常用 http（非安全上下文），`navigator.clipboard` 为 undefined 会静默失败，故保留 `execCommand('copy')` 降级。

#### 员工侧错误改写效果（实测）

| 后端原始 message | 员工看到 |
|---|---|
| `instance not reachable: no available provider for model` | 这个虚拟模型暂时不可用，请稍后再试；一直不行就联系管理员（错误码 E_PROVIDER_DOWN） |
| `quota exceeded` | 本月额度已经用完了，请联系管理员调整上限（错误码 QUOTA_EXCEEDED） |
| `invalid api key` | 你还没有这个虚拟模型的使用权限，请联系管理员开通（错误码 INVALID_KEY） |

**错误码刻意保留** —— 它是员工「找管理员」时该报的凭证，没有它员工只能描述「页面坏了」，管理员无法定位。

#### 验证

- `vue-tsc --noEmit` EXIT=0 ／ `vite build --outDir <TEMP>` EXIT=0（built in 18.79s）
- Playwright `e2e_p2_verify.mjs` **29/29 PASS**（ADMIN 10 / OPERATOR 7 / USER 7 / 权限巡检 2 / 页面说明 3）
- 权限巡检在浏览器内跑真实模块：**ERROR 0 / WARN 0**
- 一键复制申请文本是**真复制**（clipboard 写入成功 + 提示出现），不是模拟
- 截图：`frontend/.e2e_shots_p2/`

---

### 第 3 批（员工侧闭环 + 口径统一）—— ✅ 已交付 2026-11-02

批次范围：P0-A 方案 B（配额账本关闭时显式禁用多窗口字段）+ P1 员工侧 5 条 +「对外名字」→「员工用的模型名」。

#### P0-A 方案 B：把「配了却像真的」摊开

**实测判定链**（不是引用文档，是读 `ApiKeyValidator.validateFully` + `QuotaEnforcementService` 得到的）：

| 限额字段 | 账本关闭（默认） | 账本开启 |
| --- | --- | --- |
| 每分钟速率 `rateLimitPerMinute` | ✅ 生效 | ✅ 生效 |
| 每日请求上限 `dailyRequestLimit` | ✅ 生效 | ✅ 生效 |
| 每日 Token 上限 `dailyTokenLimit` | ✅ 生效 | ✅ 生效 |
| 每小时请求/Token | ❌ **不生效** | ✅ 生效 |
| 每月请求/Token | ❌ **不生效** | ✅ 生效 |

后端实测佐证：`GET /api/monitoring/quota/status` → `data.enabled = false`，
但同一响应里 `data.windows = ["MINUTE","HOUR","DAY","MONTH"]` —— **声明支持 4 个窗口，实际只有 3 个会拦**。
这正是「配了却像真的」的来源。

**做法**：新增 `composables/useQuotaLedger.ts` 单例探针（前端唯一知道「月额度是否真的会拦截」的入口），
账本关闭时在密钥配额抽屉与创建表单里：

1. 顶部弹人话告警条：「当前「每小时 / 每月」的额度不会真的拦截请求」+ 说明只有哪三项真生效；
2. 每月额度、每小时/每月高级限额输入框**显式 disabled**；
3. 每个被禁字段旁给「此项不会真的拦截请求（仅为记录）」提示；
4. **应用预设时把多窗口字段钳到 0** —— 否则预设会把非 0 值写进不生效的字段，管理员会以为「限了」。

**取不到状态时按「已启用」处理**（不禁用任何字段）：宁可少提示一次，也不能因一次网络抖动把管理员正在填的表单锁死。

#### P1 员工侧 5 条

| # | 改进 | 落地位置 | 关键点 |
| --- | --- | --- | --- |
| ① | 快速上手双分支 + 验证步骤 | `views/my/QuickstartView.vue` | 分支 A（有密钥）：复制地址 → 复制密钥 → 填进工具 → **验证**；分支 B（无密钥）：领密钥 → 等开通 → 回来照配。验证步骤给可直接粘贴的 `curl`，配了不用猜 |
| ② | 我的额度加「我能用的模型」 | `views/my/MyQuotaView.vue` | 数据源用**员工自己的密钥白名单**，不从池列表取（避免加重泄漏面） |
| ③ | 我的账单加合计 | `views/my/MyBillsView.vue` | 交叉表加「当日合计」列 + 底部合计行（`summary-method` 按列索引回调） |
| ④ | 我的密钥白名单显示友好名 | `views/my/MyKeysView.vue` | 友好名为主 + 英文名并列（`model-chip__text` / `model-chip__raw`），友好名缺失时优雅降级 |
| ⑤ | 员工侧可见范围收窄 | `composables/useEmployeeModelScope.ts` | 见下 |

#### P1 ⑤ 的实质泄漏点与修法

**泄漏点不在额度/账单，而在测试场**：测试场模型下拉原先对员工显示**全公司所有虚拟模型**
（数据源 `GET /api/config/pools/list` 返回全部池）。员工无权调用白名单外的模型，却能看到它们的名字 ——
既暴露内部模型组织方式，也让员工以为自己能用（点下去必然 403/404）。

**修法**：新增 `useEmployeeModelScope`，员工侧只保留自己密钥白名单内的虚拟模型。

**收窄口径**（与后端 `ApiKey.isModelAllowed` 语义一致）：

- 有密钥且白名单非空 → 只显示这些分组名；
- 有密钥但白名单为空 → 后端语义是「不限制」→ 显示全部；
- 没有密钥 / 取不到密钥 → 显示空，并引导去申请。

**运维侧完全不受影响** —— ops/admin 仍需看到全部模型用于排障，收窄只对员工生效（E2E 已双侧对比验证）。

#### 实施中纠正的四个问题

1. **跨登录会话缓存泄漏（越权）**：`useEmployeeModelScope` 的缓存是**模块级**的（SPA 进程内共享）。
   若不隔离，A 员工登录后登出、B 员工接着登录，B 会继承 A 的白名单状态 —— A 若持有「不限制」密钥，
   B 就会看到全公司的模型。修法：缓存键绑定当前用户名，用户切换即重置；登出时也主动清。
2. **`null` 语义被复用**：`null` 同时表示「未加载 / 加载失败 / 不限制」三种状态，无法区分。
   修法：拆出独立的 `unrestricted` 标记，`null` 只表示「未加载或失败」。
3. **配额探针把「取不到」误判成「已关闭」**：`getQuotaStatus` 内部已 `catch` 异常并返回 `null`，
   而探针写成 `status?.enabled === true` → `null` 被算成 `false`（=已关闭）→ **一次网络抖动就锁死管理员的表单**。
   修法：`status === null ? null : status.enabled === true`，把「未知」与「关闭」分开。
4. **`copy` 直接用 `navigator.clipboard`**：企业内网常见 http（非安全上下文），此时该 API 为 `undefined`，
   直接调用**静默失败**，员工点了以为复制成功了。统一改用共享的 `utils/clipboard.ts`（含 `execCommand` 降级）。

#### 口径统一：「对外名字」→「员工用的模型名」

原来「对外名字」这个说法解决不了任何一个真实困惑（员工该填什么 / 参照系是什么 / 为何看不到厂商模型）。
改成「员工用的模型名」，并加固定解释句：

> 这就是员工在 AI 工具里填的名字。填 company-chat，员工就填 company-chat —— 他不需要知道背后是 GPT 还是 Claude。

配套把「显示名」这一第二字段改称「自己看得懂的备注名」，明确它与「员工用的模型名」是两件事。
英文包原本缺 36 个键（fallback 会显原始 key），一并补齐。

#### 验证

- `vue-tsc --noEmit` EXIT=0 ／ `vite build --outDir <TEMP>` EXIT=0（built in 6.74s）
- Playwright `e2e_p3_verify.mjs` **28/28 PASS**
  （P0-A 6 / 快速上手 A 分支 5 / B 分支 4 / 额度账单 5 / 白名单与收窄 5 / 口径巡检 3）
- 回归：`e2e_p2_verify.mjs` **29/29 PASS** ／ `e2e_p1_verify.mjs` **21/21 PASS**
- 第 0 批脚本指向已下线的旧台 `:3101/models`（B 套 mock 页已下线），脚本随功能失效；
  单独验证 `/models?tab=instances` → 重定向到 `/providers?tab=models` 仍正常
- 截图：`frontend/.e2e_shots_p3/`

#### 顺带修复：自助端点 403 的真正根因

实施期间实测到员工自助端点 `/api/auth/api-keys/mine`、`/api/billing/my`、`/api/billing/my/recent` **全部 403**，
员工打开「我的密钥」「我的账单」直接不可用 —— 这让 P1 ②⑤ 无法真正生效。

排查结论：**不是代码缺陷，是运行的 jar 过期**。`PermissionRuleRegistry` 源码里已把
`/api/auth/api-keys/mine`（`security:apikeys:self`）登记在 `/api/auth/api-keys/**`（`security:apikeys:manage`）**之前**，
而 `findRule` 是「首条命中」—— 但线上跑的是 10/26 编译的旧 jar，命中的是通配规则。
重新打包后三个端点全部 200。

> **教训**：后端行为验证前必须确认 jar 与源码同步；前端 dev server 是即时的，
> 后端不是 —— 前端验证全绿不代表后端行为正确。


### 第 4 批（P0-L 越权泄漏修复 · 后端）—— ✅ 已交付（实施过程记录见文末「已交付」章节）

批次范围：P0-L 后端权限实测修复。**本批不是"体验不好"，是已坐实的越权泄漏** —— 员工能用普通账号读到不该看的数据。

#### 实测坐实的 4 条泄漏路径

原方案只列了 2 条（调用明细 + 资源池）。实施前逐行复核源码，**又查出 2 条更严重的**：

| # | 端点 | URL 规则绑定的码 | USER 持有？ | 泄漏内容 | 严重度 |
| --- | --- | --- | --- | --- | --- |
| ① | `GET /api/config/instance/{type}` | `config:instances:read` | ✅ | **上游厂商 API Key 明文** | 🔴 **凭据泄漏** |
| ② | `GET /api/config/prices/catalog` | `config:instances:read` | ✅ | 全公司厂商单价 + 熔断/限流阈值 + instanceId | 🟠 成本泄漏 |
| ③ | `GET /api/config/pools/list` | `config:pools:read` | ✅ | 全公司池成员的 instanceId / weight | 🟠 结构泄漏 |
| ④ | `GET /api/token-usage/recent` | `monitoring:tokenusage:read` | ✅ | clientIp / instanceUrl / errorMessage / userId（返回 JPA 实体全字段） | 🟠 隐私泄漏 |

**① 的完整链路**（逐行核实）：
`ServiceInstanceManager.mergeProviderAuthorization()`（`:784-796`）把厂商 apiKey 合成
`Authorization: Bearer <key>` 塞进 headers → `:136`/`:155` **连密钥落库** →
`convertToDTO()`（`:804-820`）`.headers(entity.getHeaders())` **原样带回，无脱敏** →
`getInstancesByServiceConfigId()`（`:97-102`）→ `ServiceTypeInstanceController`（`:48-67`）。

即：**员工直调一次 `/api/config/instance/chat`，就能拿到厂商密钥**。员工侧测试场当时正在调它
（`stores/playgroundCache.ts:104`）。

已核实**安全**的：`ModelProviderDTO` 只有 `hasApiKey` 布尔标记，全仓 `getApiKey()` 无进 DTO 的路径。

#### 关键决策 1：收紧权限用「显式撤销」，不改 URL 规则

`RolePermissionSeeder.reconcileUntouchedRoles()` 对既有角色的语义是**只增不删**（`:108`）。
若从 `userCodes()` 删掉那两个码而不管存量行：
1. 存量部署上 USER 仍持有该码 → `template.containsAll(held)`（`:137`）判为「含模板外权限码 → 视为手工定制」
   → **整个 USER 角色被跳过增量收敛** → 员工**依然能**访问 → 修复成了假修复；
2. 雪上加霜：USER 被判定手工定制后，将来新增任何权限码都不再补种给它 → 员工自助端点静默失效。

**最终采用的做法**（比"改 URL 规则"更直接，对存量部署同样有效）：

```java
private static final Set<String> USER_REVOKED_CODES = Set.of(
        PermissionCodes.CONFIG_POOLS_READ,
        PermissionCodes.MONITORING_TOKENUSAGE_READ,
        PermissionCodes.CONFIG_INSTANCES_READ);

/** 按「角色 + 权限码」精确删除这些行；作用范围仅限 USER 与这几个码 */
private int revokeUserRevokedCodes() { ... }
```

并在「手工定制」判定里**排除**撤销码（`:163-165`）—— 否则存量 USER 因仍持有这 3 个旧码会被误判为手工定制，
连正常的增量补种也一起失去（且越权权限并未真正收回）。删除后清权限缓存，避免启动窗口内已签发 JWT 仍带旧权限集合。

> 另一条备选（新增非 `:read` 结尾的新码 + 改绑 URL 规则 + OPERATOR 显式 add）同样可行，
> 规则表随 jar 部署即刻生效、也无需删存量行。本批选撤销方案，因其更直白、作用域更可控。
> 若沿用备选，注意新码不能以 `:read` 结尾 —— 否则 `viewerCodes()`（所有 `:read`）会自动持有，泄漏面不收敛。

#### 关键决策 2：新增端点必须**显式登记** URL 规则

`/api/instance/mine` 这类**非 `/api/config/**` 前缀**的独立端点，若不显式登记：
不匹配任何规则 → 落入 `unmatched-policy=DENY_ALL` → 仅 ADMIN 直通 → **员工恒 403**。

项目现成先例：`/api/gateway/info`（`PermissionRuleRegistry.java:144-148`）的注释写明了这一点。
反过来，若放在 `/api/config/instance/mine`，会被 `:98` 的通配规则**先命中**（首条命中优先）→ 同样 403。

#### 关键决策 3：脱敏**分层**（管理面明文 / 员工面脱敏），不落在共享转换层

`mergeProviderAuthorization` 与落库逻辑**不能改** —— 运行时路由要用真实密钥调上游，改了直接打断调用。

**第一版方案（已否决）**：在 `convertToDTO()` 这层统一脱敏。**否决理由 —— 会造成不可逆的数据损坏**：

`convertToDTO` 是管理面与自助面**共用**的转换，一旦在这里脱敏，
`GET /api/config/instance/**` 也会返回 `Authorization: "***"`，运维在「模型配置」页编辑实例时：

1. `UpstreamInstanceForm.vue:463-465` `loadInstance()` → `upstreamApiKey = "***"`（`"***"` 是 truthy）
2. `UpstreamInstanceForm.vue:399-408` `syncUpstreamApiKey()` → 写回 `headers['Authorization'] = "Bearer ***"`
3. `UpstreamInstanceForm.vue:510` `buildPayload()` → 提交 `Authorization: Bearer ***`
4. `ServiceInstanceManager:156` `.headers(request.getHeaders())` 落库 → **真实上游 Key 被永久覆盖**

真实 Key 只存在于上游厂商控制台，覆盖后该实例所有调用 401 且**无法恢复**。
（`views/providers/ModelConfigPanel.vue:1269` 还有一条同样的提交路径。）

**最终采用的分层方案**：

| 面 | 端点 | 可见角色 | headers 返回 |
| --- | --- | --- | --- |
| 管理面 | `/api/config/instance/**` | OPERATOR / ADMIN（VIEWER 见遗留项） | **明文**（运维的职责就是管理上游 Key） |
| 员工自助面 | `/api/instance/mine` | 持 `config:instances:self` 的角色（USER / ADMIN） | **脱敏**（敏感头值 = `"***"`） |

安全目标依然达成：USER 已撤销 `config:instances:read` → 管理面对员工关闭 → 员工拿不到明文；
员工改走 `/api/instance/mine`，拿到的是脱敏清单。

脱敏口径复用项目既有的 tracing 实现（`BackendCallTracingInterceptor.java:372-403`，
判定含 authorization/token/key/secret/password/credential，用 `"***"` **掩码而非剔除** —— 保持 header 存在性，
避免前端表单行为突变）。抽成 `common/util/SensitiveHeaderSanitizer`，供出参层复用。

> 顺带确认：测试场**不会**把实例 `headers` 回填进请求 —— `api/playground.ts` / `useStreaming.ts` /
> 各 Container 只带 `Jairouter_Token`（网关自身 token），上游调用由后端路由层负责。
> 因此员工面脱敏**不影响**测试场调用。

#### 修复动作清单

| 步骤 | 内容 | 落点 | 状态 |
| --- | --- | --- | --- |
| A | USER 角色显式撤销 3 个越权权限码（`config:pools:read` / `monitoring:tokenusage:read` / `config:instances:read`）+ 补 `config:instances:self` | `RolePermissionSeeder` / `PermissionCodes` | ✅ |
| A' | 脱敏分层（管理面明文 / 员工端点脱敏）+ `/api/instance/mine` + 前端按角色分流 | `SensitiveHeaderSanitizer` / `MyServiceInstanceController` / `PermissionRuleRegistry` / `playgroundCache.ts` | ✅ |
| B | `/recent` 返回 DTO，剔除 clientIp / instanceUrl / errorMessage / metadata | `TokenUsageController` / `TokenUsageRecentVO` | ✅ |
| C | `/mine` 返回 `modelScope`（poolName / displayName / serviceType / mixedPrice） | `ApiKeyService` / `ApiKeyWithModelScopeVO` | ✅ |
| D | 前端移除员工侧 `getPoolList` / `getPriceCatalog`，改用 `/mine` + `/api/instance/mine`；分组/实例缓存按用户名隔离 | `MyKeysView.vue` / `playgroundCache.ts` / `useEmployeeModelScope.ts` | ✅ |
| E | 构建验证 + 权限实测（USER 直调断言 403，OPERATOR/ADMIN 断言 200） | — | 🚧 |

> **A 的实现方式与早期设想不同**：早期方案是「新增 `config:pools:admin` / `monitoring:tokenusage:admin`
> 并改绑 URL 规则」，最终改为**显式撤销**（见关键决策 1）—— 因为 `reconcileUntouchedRoles()`
> 对既有角色是只增不删的，改 URL 规则会让存量库里的 USER 继续持有旧码并被误判为「手工定制」，
> 整个角色跳过增量收敛。撤销方案对存量部署同样生效，且作用域仅限 USER 与这 3 个码。

> **依赖关系**：A' 与 A 收紧后，员工侧必须靠自助端点顶上，否则「我的密钥」「测试场」直接 403 白屏。
> D 不是可选项，是 A/A' 的**硬依赖**。

#### 验收标准（唯一可用"证伪"方式验证的 P0）

前端菜单隐藏**不构成**安全证据。必须用 **USER 角色真实 JWT 直调后端端点**断言状态码：

- USER → `GET /api/config/instance/{type}` / `/api/config/pools/list` / `/api/config/prices/catalog` / `/api/token-usage/recent` → **403**
- USER → `GET /api/instance/mine` / `/api/auth/api-keys/mine` → **200**，且响应中**不含**任何明文密钥
- OPERATOR/ADMIN → 上述管理端点仍 **200**（权限未被误伤）

### 第 4 批（P0-L 越权泄漏修复）—— ✅ 已交付 2026-11-02

#### 交付内容（对应上文修复动作清单）

| 步骤 | 落点文件 | 实际改动 |
| --- | --- | --- |
| A | `RolePermissionSeeder.java` / `PermissionCodes.java` | `USER_REVOKED_CODES` = 3 个码；`revokeUserRevokedCodes()` 按「角色+码」精确删存量行；「手工定制」判定排除撤销码；`userCodes()` 移除 `CONFIG_INSTANCES_READ`、新增 `CONFIG_INSTANCES_SELF`；`ALL_PERMISSION_CODES` 纳入新码 |
| A' | `SensitiveHeaderSanitizer.java`（新增）/ `MyServiceInstanceController.java`（新增）/ `ServiceInstanceManager.java` / `PermissionRuleRegistry.java` | 脱敏工具；员工自助端点 `GET /api/instance/mine`（显式登记 URL 规则，绑 `config:instances:self`）；管理面 `convertToDTO` 保留明文（见关键决策 3） |
| B | `TokenUsageRecentVO.java`（新增）/ `TokenUsageController.java` | `/recent` 改返回收敛 DTO，剔除 clientIp / instanceUrl / errorMessage / metadata / provider / createdAt |
| C | `ApiKeyModelScopeResolver.java`（新增）/ `ApiKeyModelScopeVO.java` / `ApiKeyWithModelScopeVO.java` / `ApiKeyService.java` / `ApiKeyManagementController.java` | `/api/auth/api-keys/mine` 返回 `modelScope`（poolName / displayName / serviceType / mixedPrice），按密钥 + 按用户双维度 |
| D | `MyKeysView.vue` / `playgroundCache.ts` / `instance.ts` / `useEmployeeModelScope.ts` | 员工侧彻底移除 `getPoolList` / `getPriceCatalog`；测试场按角色分流（员工走 `/api/instance/mine`）；三个缓存键统一为 `${用户名}::${serviceType}`（原 `instancesCache` / `modelsCache` 未隔离，存在跨账号泄漏） |
| E | — | 4 项构建全绿 + 真实 JWT 实测 27/27 通过（见下） |

#### 实测证据（真实 JWT 直调，非前端菜单隐藏）

**启动日志 —— 撤销在存量 H2 库上真实生效**：
```
PermissionRuleRegistry 初始化完成，共登记 76 条 URL 权限规则
RolePermissionSeeder: 角色 USER 增量补种 1 个权限码: [config:instances:self]
RolePermissionSeeder: 已撤销 USER 角色的越权权限码 [config:pools:read, monitoring:tokenusage:read, config:instances:read]（共 3 条）
RolePermissionSeeder: 增量收敛完成，补种 2 条；撤销越权权限 3 条；跳过手工定制 0 个角色、无行 0 个角色、已齐备 2 个角色
```
> 「跳过手工定制 0 个角色」是关键 —— 证明撤销码被正确排除在「手工定制」判定外，没有角色被误判跳过。

**接口断言（PASS 27 / FAIL 0）**：

| 场景 | 断言 | 实测 |
| --- | --- | --- |
| USER → `/api/config/instance/{chat,embedding}` | 403 | 403 ✅ |
| USER → `/api/config/prices/catalog` | 403 | 403 ✅ |
| USER → `/api/config/pools/list` | 403 | 403 ✅ |
| USER → `/api/token-usage/recent` | 403 | 403 ✅ |
| USER → `/api/auth/api-keys/mine` / `/api/billing/my` / `/api/billing/my/recent` | 200（不回归） | 200 ✅ |
| USER → `/api/instance/mine` | 200 且 headers 脱敏 | 200，4 个实例 `headers.Authorization` 全为 `"***"` ✅ |
| OPERATOR / ADMIN → 4 个管理端点 | 200（权限未被误伤） | 200 ✅ |
| OPERATOR → `/api/config/instance/chat` | headers 明文（运维要能管理上游 Key） | `Bearer sk-5BrA8...` ✅ |
| OPERATOR / ADMIN → `/api/token-usage/recent` | 响应无敏感字段 | 无 clientIp/instanceUrl/errorMessage/metadata/provider/createdAt ✅ |

**构建**：`mvn compile` EXIT=0 / `mvn package` EXIT=0（含 test-compile）/ `vue-tsc --noEmit` EXIT=0 /
`vite build` EXIT=0（3006 modules transformed, built in 8.91s）。

#### 遗留项（需产品/运维拍板，不阻塞本批交付）

1. **已签发 JWT 仍带旧权限集合** —— 权限在 JWT 里，撤销只对**新登录**的账号生效。
   存量会话需重新登录或轮换 token 才收敛。**已实测用的是新登录账号。**
2. **VIEWER 角色仍持全部 `:read`** —— 含 `config:instances:read` → VIEWER 仍可从管理面看到明文 Key。
   若产品把 VIEWER 当「只读员工」用，需同样撤销（届时 VIEWER 需改走 `/api/instance/mine`，
   而 `config:instances:self` 不以 `:read` 结尾 → VIEWER 不持有 → 需显式补种）。
   **本批不动** —— 属未授权的产品定位变更。
3. **OPERATOR 不能调 `/api/instance/mine`**（403）—— 刻意为之：端点只服务员工自助，
   运维走管理面拿明文。已在 `PermissionRule` 注释写明，避免后人误用。
4. **密钥级均价口径** —— 从「跨分组按成员权重加权」改为「各分组均价算术平均」。
   原前端算法有 bug：用 UUID 形式的 instanceId 查以模型名为键的价格目录 → 恒返回 null，
   金额列一直显示「—」。现由服务端下发分组级均价。
5. **`TokenUsageStatistics.vue` / `QuotaUsageMonitoring.vue`** 是未挂载的孤儿组件。
6. **本机 Mockito agent 故障** —— `IllegalStateException: Could not initialize plugin: org.mockito.plugins.MockMaker`，
   既有问题（未改动过的测试同样报错），与本批无关；测试源码 `test-compile` EXIT=0。
7. **员工白名单为空时不收窄分组** —— 白名单为空即「不受限」，此时员工可见全部分组名。
   收窄需引入实例↔池关联依赖，脏数据会让测试场白屏（把安全修复变成可用性事故）。
   本批按「不收窄」处理。

### 第 4 批 · 独立代码审查（第三方复核）—— 11 项问题，已派修复 2026-11-02

交付后另派一个**只读审查员**用新鲜眼睛复核全部已定稿改动（不看实施过程，只看最终代码），
共报 11 项问题。**其中 2 项阻断级**：

| # | 级别 | 问题 | 修法 |
| --- | --- | --- | --- |
| 1 | 🔴 阻断 | `/api/instance/mine` 复用管理面 `ServiceInstanceDTO` → `errorMessage`（含上游真实报错）、`rateLimit`/`circuitBreaker`（运维阈值=成本容量信息）、`baseUrl`（上游厂商地址）全量泄漏 → **背景路径 2 并未真正关闭** | 新建员工专用 VO，只保留前端测试场真正读取的字段；`errorMessage`/`baseUrl`/阈值一律剔除 |
| 2 | 🔴 阻断 | `TokenUsageStatistics.vue:222` 渲染后端已删除的 `row.provider` → 恒为 `-` | 删列（同页 `modelName`/`instanceName` 可替代） |
| 3 | 🟠 应修 | `clearCache(serviceType)` 用 `endsWith` 匹配 → **跨用户误删**（把别人的运维专属缓存也删了） | 加 `${当前用户名}::` 前缀过滤，只清当前用户 |
| 4 | 🟠 应修 | `isOpsScope()` 受 `demoRole` 影响 + token 损坏时 catch 吞异常 → 运维误判为员工 → 拿到脱敏 `***` → 编辑实例 → **真实 Key 被永久覆盖（不可逆）** | **后端按权限码决定脱敏**（持 `config:instances:read` → 明文；纯 USER → 脱敏）→ 前端怎么误判都最多白屏，不会损坏数据；前端另修 `isOpsScope` 脱离 `demoRole` + 异常时保守判为 ops |
| 5 | 🟠 应修 | `serviceType` 大小写敏感（`chat` vs `CHAT`）→ 员工分组下拉**静默空白无提示** | 两侧统一小写归一化 |
| 6 | 🟡 应修 | `useEmployeeModelScope` 三态被压成两态（`null` 同时表示未加载/失败/不限制） | 拆出独立失败标志 |
| 7 | 🟡 应修 | 白名单为空时 `modelScope` 带全公司分组的 `displayName`+`mixedPrice` → 员工看到全公司成本水平 | **裁决**：分组名保留（员工应知自己的可调模型）；`mixedPrice`/`displayName` 在「不限制」情形下不下发 |
| 8 | 🟡 应修 | 均价口径四处不一致（成员权重加权 / 分组算术平均 / 前端再平均 / 全部密钥页加权） | **裁决**：以「密钥级合计均价」为准，服务端下发，前端只显示 |
| 9 | 🟡 应修 | `ApiKeyModelScopeResolver` 依赖缺失静默降级 → 员工看不到模型且无提示 | 响应加 `modelScopeAvailable` 标志 + 前端提示（走 i18n） |
| 10 | 🟡 应修 | `/api/instance/mine` 先 `findAll()` 全表扫再内存过滤 → 员工进一次测试场触发 7 次全表扫 | 加按 serviceType 直查的方法 |
| 11 | 🟡 应修 | `getServiceInstances` 在 `api/dashboard.ts` 与 `api/instance.ts` 重复定义 | 保留一处，grep 全仓引用后再动 |

### 审查员确认无误的部分（不必再查）
- ✅ 撤销机制在存量部署真实生效；`revokeUserRevokedCodes()` 无条件执行；撤销码正确排除在「手工定制」判定外
- ✅ `/api/instance/mine` 规则顺序正确；`config:instances:self` 不被 `operatorCodes()` 推导 → OPERATOR 不持该码
- ✅ `TokenUsageRecentVO` 已剔除 `clientIp`/`instanceUrl`/`errorMessage`/`metadata`/`provider`/`createdAt`
- ✅ `ApiKeyModelScopeVO` 不含 `instanceId`/`weight`/实例地址
- ✅ `ApiKeyService.getMineApiKeysWithModelScope` 归属判定无越权风险
- ✅ 员工侧「最近使用」走 `GET /api/call-history/mine`（`CALLHISTORY_SELF`）+ `GET /api/billing/my/recent`（`BILLING_SELF`），
  **均不受** `monitoring:tokenusage:read` 撤销影响 → 撤销不会让员工侧出现空区块
- ✅ `/api/token-usage/recent` 的 `userId`/`apiKeyId` 当前不构成员工泄漏（端点已对员工 403；ADMIN/OPERATOR 有全量读权限本就需要）

### 审查员提报的遗留（本批不做，需另立任务）
- **JWT 已签发 token 的权限窗口**：权限在 JWT claim 里，刷新时**不重算** → 撤销后旧权限最长可持续 7 天
  （前端每 30 分钟自动刷新会沿用旧 claim）。**这条直接削弱撤销效果**，建议单独立项修刷新链路。
- `MyKeysView.vue` 全篇中文硬编码，未走 i18n → en-US 下显示中文
- `TokenUsageRecentVO` 保留 `apiKeyId`/`userId`（当前不泄漏，口径待统一）
- `getServiceInstances` 重复定义（已列入上表第 11 项，一并修）
