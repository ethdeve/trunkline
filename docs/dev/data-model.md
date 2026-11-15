# 数据模型

> 基于未发版的 `db/migration/{h2,postgres}/V1__baseline.sql`（H2 635 行 / PG 641 行，共 **21 张表**）。
> 改实体、查数据、写报表前先读这里；改动流程见 [扩展指南](extending.md#5-实体变更与数据库迁移)。

## 1. 两条铁律

1. **项目尚未发版（零存量），V1 是唯一基线** —— 所有 schema 变更直接改两侧 `V1__baseline.sql` 并同步，不留增量脚本、不留存量升级包。正式发版后 V1 才视为不可改（Flyway 按校验和判定），届时改为新增 `V2__…` / `V3__…`。
2. **H2 与 PostgreSQL 双脚本必须同步** —— 两侧同名同版本号，仅物理类型不同（H2 用 `json` / `TEXT` / `enum`，PG 用 `jsonb` / `TEXT` / `varchar + check`）。由 `H2BaselineMatchesEntityModelTest` 在测试期守护两侧与实体一致。

> V1 是**合并基线**（2026-11-09）：把原 V2–V18 的演进全部纳入单文件，即"当前完善表结构"的单文件表达。原增量脚本已归档到 `db-migration-archive/`（不进 jar）。`model_price.price_cache` 因当时尚未发版而直接收进 V1（2026-11-11）。
>
> **2026-11-14 再次合并**：用户确认零存量，`model_price` → `model_quota`（B0）、#653 的 `adjustment_applied` 过滤索引（原 V2）、四个成交价快照列（原 V3）全部收进 V1；`db/stock-upgrade/` 存量升级包整体删除。当前 H2 / PG 各只有一个脚本，空库迁移历史只有 `1|SQL`。

## 2. 表全景（按职责分组）

### 2.1 身份与配置（谁在用、怎么配）

| 表 | 职责 | 关键字段 / 约束 |
|---|---|---|
| `jwt_accounts` | 登录账号（全局，平台身份/RBAC 存储） | `username` **unique**、`password`、`roles`、`department`；**无默认管理员**，首启 `/init` 向导写入 |
| `model_provider` | 供应商（厂商接入，全局） | `provider_uid` **unique**、`name`、`base_url`、`path`、`api_key`、`adapter`、`models_path`、`sort_order`、`status` |
| `service_config` | 虚拟模型 / 服务配置 | `config_key`、`service_type`、`adapter`、`load_balance_type`、`is_latest`、`version`、`tenant_id` |
| `service_instance` | 服务实例（模型的具体端点，全局） | `service_config_id` **not null**、`provider_id`（可空）、`base_url`、`instance_name`、`weight`、`headers` **json**、`tags` **json**、`health_status` |
| `config_data` | 持久化配置项 | `config_key`+`version` **unique**、`config_value` TEXT、`is_latest`、`tenant_id` |
| `config_version_history` | 配置版本快照 | `version_number`、`config_snapshot` TEXT、`status` |
| `config_audit_log` | 变更审计（新旧值） | `config_key`、`old_value`、`new_value`、`operation`、`operator`、`operator_ip`、`trace_id` |
| `config_change_audit_log` | 配置变更事件（增强审计） | `event_id`、`change_summary`、`reason`、`trace_id` |

> 关系链：`model_provider`（厂商 1）→（N）`service_instance`；`service_config`（虚拟模型 1）→（N）`service_instance`。`api_key`/`headers` 等敏感字段的落库与脱敏见第 4 节。

### 2.2 用量、配额与账单（用了多少、花了多少）

| 表 | 职责 | 关键字段 / 约束 |
|---|---|---|
| `token_usage` | **用量事实表**（请求链内写入） | `total_tokens` not null、`prompt_tokens`/`completion_tokens`、`model_name`、`user_id`、`api_key_id`、`instance_name`、`virtual_model`、`trace_id`、`is_success`、`occurred_at`、`metadata` TEXT |
| `quota_ledger` | 配额账本（预留-结算） | **唯一维度** `(tenant_id, api_key_id, user_id, service_type, model, group_id, window_type, window_start)`、`token_count`、`request_count` |
| `model_price` | 模型单价（账单数据源） | `model_name` **unique**、`price_input`/`price_output`/`price_cache` DECIMAL(12,6)、`currency`、各周期限额、多档 `quota_alert_threshold` |

> 分工：**采集**在请求链内（`TokenUsageExtractor` → `TokenUsageRecorder` 落 `token_usage`，`QuotaReservation.settleSuccess` 冲正 `quota_ledger`）；**算钱**在请求链外（`billing/service/BillingService` 按 `token_usage` + `model_price` 只读聚合）。

### 2.3 可观测与韧性（好不好用、挂没挂）

| 表 | 职责 | 关键字段 / 约束 |
|---|---|---|
| `api_call_history` | 调用历史（可配摘要/加密） | `request_id`、`trace_id`、`http_status_code`、`is_success`、`rate_limited`、`circuit_broken`、`response_time_ms`、`record_level`（SUMMARY/FULL）、`request_body_summary`/`response_body_summary`、**`request_body_encrypted`/`response_body_encrypted` TEXT** |
| `exception_events` | 异常事件（聚合） | `event_id` **unique**、`exception_type`、`exception_message`、`sanitized_message`、`sanitized_stack_trace` TEXT、`occurrence_count`、`first/last_occurrence` |
| `exception_stats_hourly` | 异常小时统计 | **唯一** `(hour_timestamp, exception_type, error_code, operation)`、`total/failure/success_count` |
| `circuit_breaker_global_config` | 熔断器全局默认参数 | `default_failure_threshold`、`default_timeout_ms`、`adaptive_threshold_enabled` 等，`id` 固定 |
| `circuit_breaker_metrics` | 熔断指标 | `instance_id`、`failure_rate`、`current/adjusted_failure_threshold`、`window_start/end` |
| `circuit_breaker_state_history` | 状态迁移历史 | `instance_id`、`previous_state`/`current_state` enum(CLOSED/HALF_OPEN/OPEN)、`trigger_reason` |
| `instance_circuit_breaker` | 实例级熔断配置 | `instance_id` **unique**、`failure_threshold`、`success_threshold`、`timeout_ms`、`enabled` |
| `instance_rate_limit` | 实例级限流配置 | `instance_id` **unique**、`capacity`、`rate`、`algorithm`、`scope`、`client_ip_enable` |

### 2.4 安全与审计（谁干了什么、拉黑了谁）

| 表 | 职责 | 关键字段 / 约束 |
|---|---|---|
| `security_audit` | 安全审计事件（全局，运维审计证据） | `event_id` **unique**、`event_type` enum(17 种：API_KEY_*/JWT_TOKEN_*/AUTHENTICATION_FAILED 等)、`risk_level` enum、`success`、`action`、`resource`、`user_id`、`details`/`failure_reason` |
| `security_blacklist` | 黑名单（IP/设备/Token，全局） | **唯一** `(blacklist_type, target_value)`、`blacklist_type` enum(DEVICE/IP/TOKEN)、`status` enum(ACTIVE/EXPIRED/REMOVED)、`source` enum(AUTO/MANUAL/SYSTEM)、`expires_at` |

> JWT 黑名单还有独立的持久化路径（`config/auth/jwt.yml` 的 `blacklist.persistence.*`，默认 h2 + 内存兜底 10000 条）；生产 profile 下存储不可达时 **fail-closed**，基础/dev 默认 fail-open（可用性优先）。见 [配置参考](configuration.md#4-profile-机制)。

## 3. 通用字段约定

| 字段 | 含义 |
|---|---|
| `tenant_id` | 租户隔离维度。仅**业务数据/计量平面**的 5 张表含此列（`config_data`、`service_config`、`token_usage`、`quota_ledger`、`api_call_history`），靠 `TenantJpaFilter` 行级过滤；**身份/上游基础设施/运维审计平面**的表（`jwt_accounts`、`model_provider`、`service_instance`、`security_blacklist`、`security_audit` 及全部熔断/遥测/配置审计流水）全局共享、无此列 |
| `id bigint generated by default as identity` | 主键，多数表通用；`circuit_breaker_global_config.id` 是固定单行配置（非 identity） |
| `created_at` / `updated_at` | 创建/更新时间戳（部分表用 `timestamp with time zone`） |
| `metadata` / `*_config` TEXT | JSON 形态的扩展字段（H2 用 TEXT，PG 侧部分用 jsonb） |
| `trace_id` | 串起一次请求的可观测标识（OTel W3C TraceContext） |

**枚举在两侧的落地差异**：H2 用原生 `enum('A','B')`，PostgreSQL 用 `varchar + check` 约束 —— 这是"两侧物理类型不同但语义一致"的典型，新增枚举字段时两侧写法都要照顾到。

## 4. 敏感数据处理

- `model_provider.api_key`：供应商密钥。**不进调用日志**；`security_audit` 的 `log-key-details`、`log-token-details` 默认 `false`（不记完整凭据）。
- `api_call_history.request_body_encrypted` / `response_body_encrypted`：`record_level=FULL` 时用 **AES-256-GCM** 加密存原文；`SUMMARY`（默认）只存截断摘要（约 200 字），不落原文。
- `config_data.config_value`、`exception_events.sanitized_stack_trace`：前者存配置（可能含敏感值，靠配置层权限约束访问），后者存已脱敏堆栈。

## 5. 方言与迁移机制

| 项 | H2（内嵌，默认） | PostgreSQL（生产） |
|---|---|---|
| 脚本目录 | `db/migration/h2/` | `db/migration/postgres/` |
| `ddl-auto` | `update`（不启用 validate，大小写/元数据探针限制） | **`validate`**（强制，由 `FlywayDialectEnvironmentPostProcessor` 以 addFirst 覆盖 core.yml 的 update） |
| 一致性守护 | `H2BaselineMatchesEntityModelTest`（测试期比对实体元数据快照 vs Flyway 快照） | 启动时 `validate` 失败即拒绝启动 |
| 方言选择 | 由 `spring.datasource.url` 判定：`jdbc:postgresql:` → PG 路径 + validate；否则 H2 | 同左 |

**空库 / 已有库**：
- 空库：Flyway 从头执行 V1，建成与实体一致的 schema（版本记为 1）。
- 已有库：`spring.flyway.baseline-on-migrate=true` 使 V1 被记为基线 1 而不执行（历史表记 `type=BASELINE`），库内容不动。

**re-baseline（仅在确认需要时）**：停实例 → `DROP TABLE flyway_schema_history` → 用干净 jar 重启。⚠️ 这会重跑 V1，仅适用于空库/测试库；生产库严禁随意 re-baseline。

## 6. 索引设计

21 张表共 60+ 个二级索引，集中在三类高频查询维度：
- **追踪/关联**：`trace_id`（api_call_history / exception_events / token_usage 均有）、`event_id`、`request_id`
- **时间范围**：`created_at` / `occurred_at` / `request_date + request_hour` / `hour_timestamp`
- **聚合维度**：`tenant_id`、`model_name`、`api_key_id`、`service_type`、`virtual_model`、`window_type + window_start`

新增查询时优先复用现有索引；高频过滤字段若无索引，直接把 `create index` 加进两侧 `V1__baseline.sql`（项目尚未发版，不存在增量脚本）。

---

Trunkline Team · Apache-2.0 · 基于 JAiRouter
