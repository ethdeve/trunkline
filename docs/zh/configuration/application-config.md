# 应用配置

<!-- 版本信息 -->
> **文档版本**: 2.0.1
> **最后更新**: 2026-09-15
> **Git 提交**: 61384b4a
> **作者**: Lincoln
<!-- /版本信息 -->

本文档详细介绍 Trunkline 的基础应用配置，包括服务器配置、WebClient 配置、监控配置等。

## 配置文件位置

| 位置 | 描述 |
|------|------|
| `src/main/resources/application.yml` | 主配置文件 |
| `src/main/resources/config/{module}/*.yml` | 模块配置文件 |
| `/app/config/*.yml` | 外部配置（Docker 部署） |
| `application-{profile}.yml` | 环境特定配置 |

## 模块化配置结构 (v2.8.x)

Trunkline 采用**模块化配置管理**方式，将复杂的配置按功能和服务模块拆分为多个独立的配置文件。

### 配置模块概览

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

### 配置导入

主配置文件 `application.yml` 导入所有模块配置（外部挂载配置必须放在 import 列表**末尾**，后导入者优先级更高）：

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

### 配置优先级

配置加载遵循以下优先级顺序（高优先级覆盖低优先级）：

1. 命令行参数（最高优先级）
2. 环境变量
3. 外部配置文件（`/app/config/`）
4. 环境特定配置文件（`application-{profile}.yml`）
5. 模块配置文件（`config/{module}/*.yml`）
6. 基础配置模块（最低优先级）

---

## 服务器配置

### 文件：`config/common/server.yml`

```yaml
server:
  port: 8080                    # 服务端口
  shutdown: graceful            # 优雅停机
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

> 早期文档中的 `server.compression.*`、`server.http2.*`、`server.netty.*` 配置块在 `config/common/server.yml` 中不存在。

---

## WebClient 配置

### 文件：`config/common/webclient.yml`

出站 HTTP 客户端连接池配置（前缀 `trunkline.webclient`）：

```yaml
trunkline:
  webclient:
    connect-timeout-ms: 5000          # 连接超时（毫秒）
    response-timeout-seconds: 60      # 响应超时（秒）
    max-connections: 500              # 连接池最大连接数
    pending-acquire-timeout-ms: 10000 # 获取连接等待超时（毫秒）
    max-idle-time-seconds: 300        # 空闲连接回收时间（秒）
```

> 早期文档中的顶层 `webclient.connect-timeout/read-timeout/write-timeout/max-in-memory-size` 与 `webclient.pool.*` 键为历史遗留（无消费者），实际生效以上述 `trunkline.webclient.*` 为准。

---

## 日志配置

### 文件：`config/common/logging.yml`

```yaml
logging:
  level:
    root: INFO
    org.unreal.modelrouter: INFO
    org.springframework: INFO
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
```

日志文件输出在各 Profile 中配置（如 `application-prod.yml` 的 `logging.file.name: logs/trunkline-prod.log`），`config/common/logging.yml` 只定义级别与控制台格式。

---

## 认证配置

### JWT 配置

#### 文件：`config/auth/jwt.yml`

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

### API 密钥配置

#### 文件：`config/auth/api-key.yml`

```yaml
trunkline:
  security:
    api-key:
      enabled: true                     # 启用 API 密钥认证
      header-name: "X-API-Key"          # API 密钥头名称
      default-expiration-days: 365      # 默认有效期（天）
      cache-enabled: true               # 启用校验缓存
```

> 早期文档中的 `api-key.hash-algorithm` 键在 `config/auth/api-key.yml` 中不存在。

---

## 路由配置

### 适配器配置

#### 文件：`config/router/adapter.yml`

```yaml
model:
  adapter: "gpustack"                     # 默认适配器（实际默认值）
  # 支持: normal, gpustack, ollama, vllm, xinference, localai, claude, gemini
```

### 负载均衡配置

#### 文件：`config/router/loadbalancer.yml`

```yaml
model:
  load-balance:
    type: "random"                        # 默认策略：random / round-robin / least-connections / ip-hash / consistent-hash / latency
```

### 限流配置

#### 文件：`config/router/ratelimit.yml`

```yaml
model:
  rate-limit:
    enabled: false                        # 默认关闭
    algorithm: "token-bucket"             # token-bucket / leaky-bucket / sliding-window / warm-up
```

### 熔断器配置

#### 文件：`config/router/circuitbreaker.yml`

```yaml
model:
  circuit-breaker:
    enabled: true
    failureThreshold: 5                   # 触发熔断的失败次数
    successThreshold: 2                   # 恢复所需成功次数
    timeout: 60000                        # OPEN 状态持续时间（毫秒）
```

---

## 监控配置

### 文件：`config/base/monitoring-base.yml`

```yaml
monitoring:
  metrics:
    prefix: "trunkline"                 # 指标前缀
    collection-interval: "30s"          # 采集间隔
    enabled-categories:                 # system / business / infrastructure / errors
      - system
      - business
```

> 早期文档中的 `trunkline.monitoring.enabled/prefix/collection-interval/enabled-categories` 嵌套前缀不正确；监控配置为扁平 `monitoring.metrics.*`。

---

## 链路追踪配置

### 文件：`config/tracing/tracing-base.yml`

```yaml
trunkline:
  tracing:
    enabled: true
    sampling:
      strategy: ratio                     # 采样策略
      ratio: 1.0                          # 采样比例
    exporter:
      type: logging                       # 导出器类型（logging / otlp 等）
```

> 早期文档中的 `tracing.sampling-rate`、`export-enabled`、`exporter-type`、`endpoint` 键不存在；实际结构为 `sampling.strategy/ratio` 与 `exporter.type`。

---

## 持久化配置

### 文件：`config/persistence/state-persistence-base.yml`

三层退坡状态持久化（Redis → H2 → 文件），前缀 `trunkline.persistence`：

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
      compress: false
    recovery:
      enabled: true
      auto-on-startup: true
      timeout: 10000
    fallback:
      auto-detect: true
      health-check-interval: 30
      max-retries: 3
      sync-on-recovery: true
```

> 早期文档中的 `trunkline.persistence.enabled/primary-storage/fallback-storage/cleanup.*` 块不存在；实际按 `redis/h2/file/recovery/fallback` 分层配置。详见 [状态持久化](state-persistence.md)。

---

## 环境特定配置

### 开发环境（`application-dev.yml`）

开发环境保持 JWT 启用；通过环境变量注入密钥，日志级别按需调高：

```yaml
logging:
  level:
    org.unreal.modelrouter: DEBUG
```

### 生产环境（`application-prod.yml`）

```yaml
trunkline:
  security:
    jwt:
      secret: "${JWT_SECRET}"           # 必须通过环境变量设置（>=32 字符）

logging:
  file:
    name: logs/trunkline-prod.log       # 生产环境输出日志文件
  level:
    root: INFO
```

---

## 外部配置（Docker）

使用 Docker 部署时，可以挂载外部配置文件：

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

## 环境变量

| 变量 | 描述 | 默认值 |
|------|------|--------|
| `JWT_SECRET` | JWT 签名密钥 | （生产环境必填） |
| `INITIAL_ADMIN_PASSWORD` | 初始管理员密码 | （生产环境必填） |
| `REDIS_HOST` | Redis 主机 | localhost |
| `REDIS_PORT` | Redis 端口 | 6379 |
| `REDIS_PASSWORD` | Redis 密码 | （空） |
| `SERVER_PORT` | 服务器端口 | 8080 |

---

## 配置最佳实践

1. **使用环境变量**存储敏感值（密钥、密码）
2. **使用外部配置**进行 Docker 部署
3. **使用环境特定配置**区分不同环境
4. **保持模块配置**专注和独立
5. **在部署指南中记录**自定义配置

---

*最后更新：2026-05-21*
