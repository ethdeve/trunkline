# 变更记录 / Changelog

本项目遵循语义化版本。这里只记录**已经落地**的变化，后续计划见 [README](README-ZH.md)。

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
- **破坏性**：V1 是 Flyway 基线，内容改写会导致 checksum 变化，**仅适用于尚未执行过 V1 的
  全新安装**（本项目尚未发版，当前内测形态为零存量）。任何已执行过 V1 的存量库，升级前
  必须先执行 `SELECT version, checksum, success FROM flyway_schema_history WHERE version = '1'`
  确认，并改走 `V4__split_model_price_to_quota.sql` 路径（先导出旧价格归档，再 rename +
  drop 价格列 + 改约束名），**禁止直接覆盖升级**。
- 判定与迁移细则见《Trunkline-计价能力商业化代码改造方案》P0-2 / 3.4。

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
