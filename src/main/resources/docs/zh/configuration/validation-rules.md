# 配置验证规则

<!-- 版本信息 -->
> **文档版本**: 1.1.0  
> **最后更新**: 2026-10-28
> **作者**: Lincoln
<!-- /版本信息 -->

Trunkline 在启动时与管理端对配置执行敏感配置校验与完整性校验，并通过管理 API 暴露校验规则与配置来源。

## 管理 API

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/config/sources` | GET | 查看各配置项的加载来源（YAML / 环境变量 / 动态配置） |
| `/api/config/validation-rules` | GET | 查看校验规则定义 |
| `/api/config/environment-variables` | GET | 必需/可选环境变量清单与说明 |

```bash
# 查看校验规则定义
curl -X GET "http://localhost:8080/api/config/validation-rules"

# 查看环境变量清单
curl -X GET "http://localhost:8080/api/config/environment-variables"

# 查看配置来源追踪
curl -X GET "http://localhost:8080/api/config/sources"
```

## 校验规则分类

校验规则分两类（来源 `ConfigValidationController`）：

### 1. 敏感配置规则（sensitiveConfig）

校验敏感配置是否通过环境变量注入、是否达到最小长度：

| 规则 | 说明 |
|------|------|
| `JWT_SECRET` | JWT 密钥，最小长度 32 字符，必须通过环境变量 `JWT_SECRET` 注入 |
| `INITIAL_ADMIN_PASSWORD` | 管理员初始密码，最小长度 8，必须通过环境变量注入 |
| `GPUSTACK_API_TOKEN` | GPUStack API Token（使用 GPUStack 适配器时必填） |

校验失败会在启动日志中输出错误并阻止启动（或标记为缺失）。

### 2. 完整性配置规则（integrityConfig）

校验关键业务配置是否在合法区间：

| 配置项 | 合法范围 | 默认值 |
|--------|----------|--------|
| `server.port` | 1 - 65535 | 8080 |
| `model.load-balance.type` | 枚举：`round-robin` / `weighted` / `least-connections` / `ip-hash` / `random` | `round-robin`（注：运行时实际支持 `random`/`round-robin`/`least-connections`/`ip-hash`/`consistent-hash`/`latency`，枚举以控制器规则表为准） |
| `model.circuit-breaker.enabled` | 布尔 | true |
| `model.circuit-breaker.failureThreshold` | 最小 1 | 5 |
| `model.circuit-breaker.timeoutMs` | 100 - 300000 | 60000 |
| `store.type` | 枚举：`jpa` / `redis` / `memory` | `jpa` |

## 必需环境变量

启动时强制要求（缺失则告警/阻止启动）：

| 环境变量 | 说明 | 最小长度 |
|----------|------|----------|
| `JWT_SECRET` | JWT 签名密钥 | 32 |
| `INITIAL_ADMIN_PASSWORD` | 管理员初始密码 | 8 |

## 可选环境变量

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `GPUSTACK_API_TOKEN` | - | GPUStack 适配器 API Token |
| `JWT_EXPIRATION_MINUTES` | 60 | 访问令牌有效期（分钟） |
| `JWT_REFRESH_EXPIRATION_DAYS` | 7 | 刷新令牌有效期（天） |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | localhost/6379/空 | Redis 连接（分布式限流、状态持久化 Tier1、Redis 存储时使用） |
| `SERVER_PORT` | 8080 | 服务端口 |
| `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` | H2 默认 | 切换 PostgreSQL |

> **待确认**：早期版本文档描述的「启动校验横幅 + 7 条规则（RATE_LIMIT_CAPACITY / RATE_LIMIT_RATE / RATE_LIMIT_SCOPE / CIRCUIT_BREAKER_FAILURE_THRESHOLD / CIRCUIT_BREAKER_TIMEOUT / MODEL_SERVICES_INSTANCES / JWT_EXPIRATION）」及其对应的 `RATE_LIMIT_CAPACITY` 等环境变量，在当前代码中**未找到对应实现**（`grep RATE_LIMIT_CAPACITY` 零命中）。上述校验以 `/api/config/validation-rules` 返回的规则表为准。

## 与其他配置的关系

- 敏感配置校验是部署安全的第一道防线，与 [JWT 持久化](jwt-persistence.md) 配合
- 完整性校验覆盖 [负载均衡](load-balancing.md)、[熔断器](circuit-breaker.md)、[存储配置](store-config.md) 的关键参数
- 配置来源追踪帮助定位「为什么配置没生效」，与 [动态配置](dynamic-config.md) 配合

## 下一步

- [动态配置](dynamic-config.md) - 运行时配置管理
- [JWT 持久化](jwt-persistence.md) - JWT 令牌与审计配置
- [存储配置](store-config.md) - 存储类型与迁移
