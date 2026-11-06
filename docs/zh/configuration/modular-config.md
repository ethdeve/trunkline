# 模块化配置指南

<!-- 版本信息 -->
> **文档版本**: 2.0.1
> **最后更新**: 2026-09-15
> **Git 提交**: 61384b4a
> **作者**: Lincoln
<!-- /版本信息 -->

## 概述

Trunkline 采用**模块化配置管理**方式，将复杂的配置按功能和服务模块拆分为多个独立的配置文件。这种设计提高了配置的可维护性、可读性和可重用性。

## 配置模块结构 (v2.8.x)

### 目录结构

```
config/
├── common/              # 公共基础设施
│   ├── server.yml       # 服务器配置
│   ├── webclient.yml    # WebClient 配置
│   ├── logging.yml      # 日志配置
│   ├── scheduling.yml   # 定时任务开关
│   └── cluster.yml      # 集群开关
├── config-service/      # 配置服务
│   └── core.yml         # ConfigurationService 设置
├── router/              # 路由服务
│   ├── adapter.yml      # 适配器配置
│   ├── loadbalancer.yml # 负载均衡配置
│   ├── ratelimit.yml    # 限流配置
│   ├── circuitbreaker.yml # 熔断器配置
│   ├── fallback.yml     # 降级配置
│   ├── services.yml     # 服务实例配置
│   ├── rules.yml        # 条件路由规则
│   └── pools.yml        # 模型分组（资源池）
├── auth/                # 认证服务
│   ├── jwt.yml          # JWT 认证配置
│   ├── api-key.yml      # API 密钥配置
│   ├── audit.yml        # 审计配置
│   └── sanitization.yml # 数据脱敏配置
├── monitor/             # 监控服务
│   ├── slow-query-alerts.yml
│   ├── error-tracking.yml
│   └── persistence-monitoring-base.yml
├── tracing/             # 链路追踪服务
│   └── tracing-base.yml
├── persistence/         # 持久化服务
│   └── state-persistence-base.yml
├── base/                # 基础配置（遗留）
│   ├── server-base.yml
│   ├── model-services-base.yml
│   └── monitoring-base.yml
└── security/            # 安全配置（遗留）
    ├── security-base.yml
    ├── persistence-base.yml
    └── audit-base.yml
```

### 模块分类

| 模块 | 目录 | 用途 |
|------|------|------|
| **Common** | `config/common/` | 共享基础设施（服务器、WebClient、日志） |
| **Config Service** | `config/config-service/` | 配置管理服务 |
| **Router** | `config/router/` | 路由、负载均衡、限流、熔断器 |
| **Auth** | `config/auth/` | 认证（JWT、API Key）、审计、脱敏 |
| **Monitor** | `config/monitor/` | 性能监控、错误追踪 |
| **Tracing** | `config/tracing/` | 分布式链路追踪 |
| **Persistence** | `config/persistence/` | 状态持久化 |

---

## 主配置导入

### application.yml

```yaml
spring:
  config:
    import:
      # common 模块
      - classpath:config/common/server.yml
      - classpath:config/common/webclient.yml
      - classpath:config/common/logging.yml
      - classpath:config/common/scheduling.yml
      - classpath:config/common/cluster.yml

      # config-service 模块
      - classpath:config/config-service/core.yml

      # router 模块
      - classpath:config/router/adapter.yml
      - classpath:config/router/loadbalancer.yml
      - classpath:config/router/ratelimit.yml
      - classpath:config/router/circuitbreaker.yml
      - classpath:config/router/fallback.yml
      - classpath:config/router/services.yml
      - classpath:config/router/rules.yml
      - classpath:config/router/pools.yml

      # auth 模块
      - classpath:config/auth/jwt.yml
      - classpath:config/auth/api-key.yml
      - classpath:config/auth/audit.yml
      - classpath:config/auth/sanitization.yml

      # monitor / tracing / persistence 模块
      - classpath:config/base/monitoring-base.yml
      - classpath:config/monitor/slow-query-alerts.yml
      - classpath:config/monitor/error-tracking.yml
      - classpath:config/monitor/persistence-monitoring-base.yml
      - classpath:config/tracing/tracing-base.yml
      - classpath:config/persistence/state-persistence-base.yml

      # 向后兼容配置
      - classpath:config/base/server-base.yml
      - classpath:config/base/model-services-base.yml
      - classpath:config/security/security-base.yml
      - classpath:config/security/persistence-base.yml
      - classpath:config/security/audit-base.yml

      # 外部配置（Docker 挂载，必须放最后）
      - optional:file:/app/config/application.yml
      - optional:file:/app/config/auth/jwt.yml
      - optional:file:/app/config/router/services.yml
```

---

## 配置优先级

配置加载遵循以下优先级顺序（高优先级覆盖低优先级）：

1. 命令行参数（最高优先级）
2. 环境变量
3. 外部配置文件（`/app/config/`）
4. 环境特定配置文件（`application-{profile}.yml`）
5. 模块配置文件（`config/{module}/*.yml`）
6. 基础配置模块（最低优先级）

---

## 模块配置详解

### Common 模块

#### 服务器配置（`config/common/server.yml`）

```yaml
server:
  port: 8080                    # 服务端口
  shutdown: graceful            # 优雅停机
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

> 早期文档中的 `server.compression.*`、`server.http2.*` 配置块在 `config/common/server.yml` 中不存在。

#### WebClient 配置（`config/common/webclient.yml`）

```yaml
trunkline:
  webclient:
    connect-timeout-ms: 5000          # 连接超时（毫秒）
    response-timeout-seconds: 60      # 响应超时（秒）
    max-connections: 500              # 连接池最大连接数
    pending-acquire-timeout-ms: 10000 # 获取连接等待超时（毫秒）
    max-idle-time-seconds: 300        # 空闲连接回收时间（秒）
```

> 早期文档中的顶层 `webclient.*` 键为历史遗留（无消费者），实际生效以上述 `trunkline.webclient.*` 为准。

#### 日志配置（`config/common/logging.yml`）

```yaml
logging:
  level:
    root: INFO
    org.unreal.modelrouter: INFO
    org.springframework: INFO
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
```

---

### Router 模块

#### 适配器配置（`config/router/adapter.yml`）

```yaml
model:
  adapter: "gpustack"                     # 默认适配器（实际默认值）
  # 支持: normal, gpustack, ollama, vllm, xinference, localai, claude, gemini
```

#### 负载均衡配置（`config/router/loadbalancer.yml`）

```yaml
model:
  load-balance:
    type: "random"                        # random / round-robin / least-connections / ip-hash / consistent-hash / latency
```

#### 限流配置（`config/router/ratelimit.yml`）

```yaml
model:
  rate-limit:
    enabled: false                        # 默认关闭
    algorithm: "token-bucket"             # token-bucket / leaky-bucket / sliding-window / warm-up
```

#### 熔断器配置（`config/router/circuitbreaker.yml`）

```yaml
model:
  circuit-breaker:
    enabled: true
    failureThreshold: 5                   # 触发熔断的失败次数
    successThreshold: 2                   # 恢复所需成功次数
    timeout: 60000                        # OPEN 状态持续时间（毫秒）
```

---

### Auth 模块

#### JWT 配置（`config/auth/jwt.yml`）

```yaml
trunkline:
  security:
    jwt:
      enabled: true                     # 启用 JWT 认证
      jwt-header: "Jairouter_Token"     # 自定义 JWT 头
      secret: "${JWT_SECRET:}"          # JWT 密钥（使用环境变量，>=32 字符）
      algorithm: "HS256"                # 签名算法
      expiration-minutes: 60            # 访问令牌过期时间
      refresh-expiration-days: 7        # 刷新令牌过期时间
      issuer: "trunkline"               # 令牌签发者
      blacklist-enabled: true           # 启用令牌黑名单
```

#### API 密钥配置（`config/auth/api-key.yml`）

```yaml
trunkline:
  security:
    api-key:
      enabled: true                     # 启用 API 密钥认证
      header-name: "X-API-Key"          # API 密钥头名称
      default-expiration-days: 365      # 默认有效期（天）
```

> 早期文档中的 `api-key.hash-algorithm` 键在 `config/auth/api-key.yml` 中不存在。

#### 审计配置（`config/auth/audit.yml`）

```yaml
trunkline:
  security:
    audit:
      enabled: true                     # 启用审计日志
      log-level: "INFO"
      include-request-body: false       # 生产环境建议关闭
      include-response-body: false
      retention-days: 90
```

> 早期文档中的 `audit.max-body-length` 键不存在；请求体记录由 `include-request-body` 布尔开关控制。

#### 数据脱敏配置（`config/auth/sanitization.yml`）

```yaml
trunkline:
  security:
    sanitization:
      enabled: true                     # 启用数据脱敏
      response:
        sensitive-words: []             # 敏感词列表
        pii-patterns: []                # PII 正则
        masking-char: "*"               # 脱敏字符
```

> 早期文档中的 `sanitization.mask-sensitive-fields` 列表键不存在；实际配置为 `response.sensitive-words/pii-patterns/masking-char`。

---

### Monitor 模块

#### 慢查询告警（`config/monitor/slow-query-alerts.yml`）

```yaml
trunkline:
  monitoring:
    slow-query-alert:
      enabled: true
      global:
        enabled: true
        min-interval-ms: 300000         # 告警冷却时间
```

#### 错误追踪（`config/monitor/error-tracking.yml`）

```yaml
trunkline:
  monitoring:
    error-tracking:
      enabled: true                     # 默认开启
      aggregation-window-minutes: 5     # 聚合窗口
      sanitization:
        enabled: true
```

> 早期文档中 `error-tracking.enabled: false` 与实际 `true` 不符。

---

### Tracing 模块

#### 链路追踪配置（`config/tracing/tracing-base.yml`）

```yaml
trunkline:
  tracing:
    enabled: true
    sampling:
      strategy: ratio                   # 采样策略
      ratio: 1.0                        # 采样比例
    exporter:
      type: logging                     # 导出器类型
```

> 早期文档中的 `tracing.sampling-rate`、`export-enabled`、`exporter-type`、`endpoint` 键不存在；实际结构为 `sampling.strategy/ratio` 与 `exporter.type`。

---

### Persistence 模块

#### 状态持久化（`config/persistence/state-persistence-base.yml`）

三层退坡（Redis → H2 → 文件），前缀 `trunkline.persistence`：

```yaml
trunkline:
  persistence:
    redis:
      enabled: false
      host: localhost
      port: 6379
      ttl-days: 7
    h2:
      enabled: true
      data-dir: ./data
      database: trunkline_state
    file:
      enabled: true
      path: ./data/state
    recovery:
      enabled: true
      auto-on-startup: true
      timeout: 10000
    fallback:
      auto-detect: true
      health-check-interval: 30
      max-retries: 3
```

> 早期文档中的 `trunkline.persistence.enabled/primary-storage/fallback-storage/cleanup.*` 块不存在；实际按 `redis/h2/file/recovery/fallback` 分层配置。

---

## 环境特定配置

### 开发环境（`application-dev.yml`）

```yaml
spring:
  config:
    activate:
      on-profile: dev

logging:
  level:
    root: DEBUG

trunkline:
  security:
    jwt:
      enabled: false                    # 开发环境禁用 JWT
  monitoring:
    enabled: false                      # 开发环境禁用监控
```

### 生产环境（`application-prod.yml`）

```yaml
spring:
  config:
    activate:
      on-profile: prod

logging:
  level:
    root: INFO

trunkline:
  security:
    jwt:
      enabled: true
      secret: "${JWT_SECRET}"           # 必须通过环境变量设置
  monitoring:
    enabled: true
```

---

## 外部配置（Docker）

挂载外部配置文件覆盖默认值：

```bash
docker run -d \
  --name trunkline \
  -p 8080:8080 \
  -v /path/to/config:/app/config \
  -e JWT_SECRET="your-secret-key" \
  trunkline/trunkline:latest
```

### 外部配置结构

```
/app/config/
├── application.yml      # 覆盖主配置
├── auth/
│   └── jwt.yml          # 覆盖 JWT 配置
└── router/
    └── services.yml     # 覆盖服务实例配置
```

---

## 配置最佳实践

1. **使用环境变量**存储敏感值（密钥、密码）
2. **使用外部配置**进行 Docker 部署
3. **使用环境特定配置**区分不同环境
4. **保持模块配置**专注和独立
5. **在部署指南中记录**自定义配置

---

## 故障排除

### 配置未生效

1. 检查配置文件路径
2. 确认环境配置文件是否加载
3. 验证配置优先级顺序
4. 检查是否有语法错误

### 配置冲突

1. 理解配置优先级规则
2. 检查是否有重复配置项
3. 确认环境变量是否覆盖了预期配置
4. 使用 `--debug` 参数查看配置加载过程

---

*最后更新：2026-05-21*
