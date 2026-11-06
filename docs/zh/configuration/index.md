# 配置指南

<!-- 版本信息 -->
> **文档版本**: 1.1.0  
> **最后更新**: 2026-10-28
> **作者**: Lincoln
<!-- /版本信息 -->

Trunkline 提供灵活的配置选项以满足各种部署场景。本指南涵盖从基本设置到高级功能的所有配置方面。

## 模块化配置说明

从 v2.8.x 版本开始，Trunkline 采用模块化配置结构，将不同功能的配置分离到独立的配置文件中，以提高可维护性和可读性。

### 配置文件结构

```
src/main/resources/
├── application.yml                    # 主配置文件，导入其他模块
├── config/
│   ├── common/                        # 公共基础设施
│   │   ├── server.yml                 # 服务器配置
│   │   ├── webclient.yml              # WebClient 连接池配置
│   │   ├── logging.yml                # 日志配置
│   │   ├── scheduling.yml             # 定时任务开关
│   │   └── cluster.yml                # 集群开关
│   ├── config-service/
│   │   └── core.yml                   # 配置服务核心（store/数据源/JPA）
│   ├── router/                        # 路由服务
│   │   ├── adapter.yml                # 适配器配置
│   │   ├── loadbalancer.yml           # 负载均衡配置
│   │   ├── ratelimit.yml              # 限流配置
│   │   ├── circuitbreaker.yml         # 熔断器配置
│   │   ├── fallback.yml               # 降级配置
│   │   ├── services.yml               # 服务实例配置
│   │   ├── rules.yml                  # 条件路由规则
│   │   └── pools.yml                  # 模型分组（资源池）
│   ├── auth/                          # 认证服务
│   │   ├── jwt.yml                    # JWT 认证配置
│   │   ├── api-key.yml                # API 密钥配置
│   │   ├── audit.yml                  # 审计配置
│   │   └── sanitization.yml           # 数据脱敏配置
│   ├── monitor/                       # 监控服务
│   │   ├── slow-query-alerts.yml
│   │   ├── error-tracking.yml
│   │   └── persistence-monitoring-base.yml
│   ├── tracing/
│   │   └── tracing-base.yml           # 链路追踪
│   ├── persistence/
│   │   └── state-persistence-base.yml # 状态持久化（三层退坡）
│   ├── base/                          # 基础配置（向后兼容）
│   │   ├── server-base.yml
│   │   ├── model-services-base.yml
│   │   └── monitoring-base.yml
│   └── security/                      # 安全配置（向后兼容）
│       ├── security-base.yml
│       ├── persistence-base.yml
│       └── audit-base.yml
├── application-dev.yml                # 开发环境配置
├── application-staging.yml            # 预发布环境配置
├── application-prod.yml               # 生产环境配置
├── application-legacy.yml             # 向后兼容配置
└── application-migrate.yml            # 数据迁移配置
```

### 配置导入机制

主配置文件 `application.yml` 通过 `spring.config.import` 机制导入各个模块配置：

```yaml
# application.yml
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
      # 外部配置文件（必须放在 import 列表末尾，后导入者优先级更高）
      - optional:file:/app/config/application.yml
      - optional:file:/app/config/auth/jwt.yml
      - optional:file:/app/config/router/services.yml
```

这种方式使得配置更加清晰，便于维护和扩展。

## 配置概览

Trunkline 支持两种主要配置方式：

1. **静态配置**: 在 YAML 文件中定义，启动时加载
2. **动态配置**: 通过 REST API 在运行时更新（实例、限流、熔断、规则、分组等）

## 配置层次结构

配置按以下顺序加载（后面的源会覆盖前面的）：

1. 默认配置（嵌入在 JAR 中）
2. `application.yml`（classpath）及其 `spring.config.import` 导入的模块配置
3. `./application.yml`（当前目录）
4. `./config/application.yml`（config 目录）
5. 环境变量
6. 命令行参数
7. 动态配置（运行时更新，持久化到 StoreManager）

## 基本结构

```yaml
server:
  port: 8080

model:
  services:
    <服务类型>:
      load-balance:
        type: <策略>
      rate-limit:
        algorithm: <算法>
        # ... 限流设置
      circuit-breaker:
        enabled: true
        # ... 熔断器设置
      fallback:
        strategy: <回退类型>
        # ... 回退设置
      instances:
        - name: <模型名称>
          base-url: <服务URL>
          path: <API路径>
          weight: <负载均衡权重>
          # ... 实例特定设置

store:
  type: jpa            # 默认 jpa
  path: ./config       # 配置文件导入导出目录
  fallback-storage: memory
```

## 服务类型

Trunkline 支持以下服务类型：

| 服务类型 | 描述 | 默认路径 |
|----------|------|----------|
| `chat` | 聊天对话 | `/v1/chat/completions` |
| `embedding` | 文本嵌入 | `/v1/embeddings` |
| `rerank` | 文本重排 | `/v1/rerank` |
| `tts` | 语音合成 | `/v1/audio/speech` |
| `stt` | 语音识别 | `/v1/audio/transcriptions` |
| `imgGen` | 图像生成 | `/v1/images/generations` |
| `imgEdit` | 图像编辑 | `/v1/images/edits` |

## 配置部分

### 1. 负载均衡

配置请求如何在服务实例之间分发（配置文件 `config/router/loadbalancer.yml`）：

```yaml
model:
  load-balance:
    type: round-robin        # 默认 random
    hash-algorithm: md5      # ip-hash / consistent-hash 的哈希算法
    virtual-nodes: 150       # 一致性哈希虚拟节点数
    ewma-alpha: 0.2          # latency 策略的 EWMA 平滑因子
```

**可用策略：**

- **random**: 随机选择实例（默认）
- **round-robin**: 按顺序轮询实例
- **least-connections**: 路由到活跃连接最少的实例
- **ip-hash**: 基于客户端 IP 哈希的会话粘性路由
- **consistent-hash**: 一致性哈希（带虚拟节点，实例上下线影响小）
- **latency**: 基于 EWMA 历史延迟的加权随机（延迟越低权重越高）

### 2. 限流

控制请求速率以防止服务过载（配置文件 `config/router/ratelimit.yml`）：

```yaml
model:
  rate-limit:
    enabled: false
    algorithm: token-bucket   # 默认 token-bucket
    capacity: 1000
    rate: 100
    scope: service            # service / instance / global
    client-ip-enable: false   # 启用基于客户端IP的限流
    key-dimension: client-ip  # client-ip / api-key / tenant
```

**可用算法（以 `ComponentFactory` 实现为准）：**

- **token-bucket**: 令牌桶，允许突发流量直到桶容量（默认）
- **leaky-bucket**: 漏桶，平滑的恒定速率输出
- **sliding-window**: 滑动窗口，精确时间窗口控制
- **warm-up**: 预热，启动时逐渐爬升速率

### 3. 熔断器

使用熔断器模式防止级联故障（配置文件 `config/router/circuitbreaker.yml`）：

```yaml
model:
  circuit-breaker:
    enabled: true
    failureThreshold: 5      # 打开熔断器前的失败次数
    timeout: 60000           # OPEN 状态持续时间（毫秒），过后进入 HALF_OPEN
    successThreshold: 2      # HALF_OPEN 关闭熔断器需要的成功次数
```

### 4. 回退策略

定义服务不可用时的回退行为（配置文件 `config/router/fallback.yml`）：

```yaml
model:
  fallback:
    enabled: true
    strategy: default        # default / cache / mock
```

**可用策略：**

- **default**: 返回预定义降级响应
- **cache**: 返回最近一次成功的缓存响应（rerank 降级缓存默认 `cache-size: 100`、`cache-ttl: 600000` 毫秒）
- **mock**: 返回 Mock 响应

### 5. 服务实例

定义实际的服务端点（配置文件 `config/router/services.yml`）：

```yaml
model:
  services:
    chat:
      instances:
        - name: "qwen2.5:7b"
          base-url: "http://server1:11434"
          path: "/v1/chat/completions"
          weight: 2
          headers:
            Authorization: "Bearer token"
        - name: "qwen2.5:14b"
          base-url: "http://server2:11434"
          path: "/v1/chat/completions"
          weight: 1
```

**实例属性：**

- `name`: 模型名称标识符
- `base-url`: 服务基础URL（YAML 中推荐 kebab-case）
- `path`: API端点路径
- `weight`: 负载均衡权重（越高获得越多流量）
- `instance-id`: 实例唯一标识（缺省时由 `name@baseUrl` 生成）
- `adapter`: 实例级适配器（缺省使用服务级/全局适配器）
- `headers`: 请求中包含的自定义头部
- `tags`: 实例标签（键值对，用于标签路由圈选）

### 6. 存储配置

配置动态配置的持久化方式（配置文件 `config/config-service/core.yml`）：

```yaml
store:
  type: jpa                 # 默认 jpa（H2），可选 redis / memory
  path: ./config            # 配置文件导入导出目录
  fallback-storage: memory  # 备用存储类型（降级）
  migration:
    enabled: true           # 从旧文件存储迁移到 JPA
  security-migration:
    enabled: true           # 安全数据（API Key/JWT 账户）迁移
```

数据源默认 H2（`spring.datasource.url: jdbc:h2:file:./data/trunkline`），可通过环境变量 `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` 切换到 PostgreSQL。

## 环境特定配置

Trunkline 支持多种环境配置文件：

- **开发环境**: `application-dev.yml`
- **预发布环境**: `application-staging.yml`
- **生产环境**: `application-prod.yml`
- **兼容模式**: `application-legacy.yml`
- **数据迁移**: `application-migrate.yml`

环境配置文件只包含与基础配置的差异部分，遵循 Spring Boot 的配置覆盖机制。

## 环境变量

使用环境变量覆盖配置（敏感配置必须使用环境变量）：

```bash
# 必需：JWT 密钥（长度 >= 32 字符）与管理员初始密码
export JWT_SECRET="your-secret-at-least-32-chars"
export INITIAL_ADMIN_PASSWORD="your-admin-password"

# 服务器端口
export SERVER_PORT=8080

# 模型服务配置（Spring Boot relaxed binding 规则）
export MODEL_LOAD_BALANCE_TYPE=round-robin
export MODEL_RATE_LIMIT_ALGORITHM=token-bucket

# Redis（如启用分布式计数 / 状态持久化 Tier1）
export REDIS_HOST=localhost
export REDIS_PORT=6379
export REDIS_PASSWORD=""
```

## 命令行参数

通过命令行参数覆盖配置：

```bash
java -jar trunkline.jar \
  --server.port=8080 \
  --model.load-balance.type=round-robin \
  --spring.profiles.active=prod
```

## 配置验证

Trunkline 在启动时对敏感配置与关键完整性配置进行校验，管理端可通过以下 API 查询校验规则与配置来源：

- `GET /api/config/sources`：查看各配置项的加载来源
- `GET /api/config/validation-rules`：查看校验规则定义（敏感配置最小长度、负载均衡策略枚举、熔断器阈值范围等）
- `GET /api/config/environment-variables`：必需/可选环境变量清单

详见 [验证规则](validation-rules.md)。

## 配置最佳实践

### 1. 使用有意义的名称

```yaml
# ✅ 好 - 描述性名称
model:
  services:
    chat:
      instances:
        - name: "qwen2.5-7b-fast"
          base-url: "http://fast-gpu-server:11434"
        - name: "qwen2.5-14b-accurate"
          base-url: "http://high-memory-server:11434"
```

### 2. 使用权重进行渐进式部署

```yaml
# ✅ 好 - 使用权重渐进式部署
model:
  services:
    chat:
      instances:
        - name: "stable-model-v1"
          base-url: "http://stable-server:11434"
          weight: 9  # 90% 流量
        - name: "new-model-v2"
          base-url: "http://new-server:11434"
          weight: 1  # 10% 流量
```

### 3. 敏感配置使用环境变量

JWT 密钥、管理员密码、GPUStack Token、Redis 密码等一律通过环境变量注入，不要写死在 YAML 中。

## 下一步

- [应用配置](application-config.md) - 详细的应用设置
- [动态配置](dynamic-config.md) - 运行时配置管理
- [负载均衡](load-balancing.md) - 负载均衡策略
- [限流配置](rate-limiting.md) - 限流算法
- [熔断器](circuit-breaker.md) - 熔断器配置
- [存储配置](store-config.md) - 存储与数据迁移配置
- [模型分组](model-pools.md) - 模型池（资源池）配置与分组级配额
- [版本管理](version-management.md) - 配置版本管理：应用 vs 回滚
- [KV缓存](kv-cache.md) - 前缀缓存友好网关：粘性路由与缓存指标
- [响应缓存](response-cache.md) - 完整响应缓存：确定性请求命中直接复用响应、跳过下游
- [配额配置](quota.md) - 多维配额账本
