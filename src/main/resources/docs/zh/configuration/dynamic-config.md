# 动态配置

<!-- 版本信息 -->
> **文档版本**: 1.0.2  
> **最后更新**: 2026-05-21
> **Git 提交**: 61384b4a  
> **作者**: Lincoln
<!-- /版本信息 -->

Trunkline 提供灵活的配置选项以满足各种部署场景。本指南涵盖从基本设置到高级功能的所有配置方面。

## 模块化配置说明

从 v1.0.0 版本开始，Trunkline 采用模块化配置结构：

- 主配置文件：`application.yml`
- 基础配置模块：`config/base/` 目录下的文件
- 功能配置模块：`config/security/`、`config/tracing/` 等目录下的文件
- 环境配置文件：`application-dev.yml`、`application-prod.yml` 等

虽然配置已经模块化，动态配置 API 仍然可以在运行时更新实例配置，且不影响模块化结构。

## 配置概览
**1. 配置合并**：启动时读取 config 目录中的配置文档，并自动与 application.yml 配置合并，以实现动态更新。
**2. 实例管理 API**：通过 REST API 在运行时更新配置。

## 配置合并与持久化

启动时 YAML 模块配置与运行时持久化配置（StoreManager，默认 JPA/H2）自动合并：YAML 为默认值，管理端动态修改持久化后覆盖 YAML。动态配置以 JSON 形式存储，结构见下一节「持久化配置结构」。

> 早期文档中描述的 `model-router-config@<version>.json` 多版本文件目录与 `/api/config/merge/*` 系列端点（scan/preview/execute/backup/batch/cleanup/validate/statistics/status）在当前版本**已不存在**。配置的历史版本管理请使用 [版本管理](version-management.md) 提供的 `/api/config/version/*` API。

## 实例管理 API

### API 端点概览

实例管理基路径为 `/api/config/instance`（`ServiceTypeInstanceController`）：

| 操作 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 获取实例列表 | GET | `/api/config/instance/{serviceType}` | 获取指定服务的所有实例 |
| 添加实例 | POST | `/api/config/instance/{serviceType}` | 添加新的服务实例 |
| 更新实例 | PUT | `/api/config/instance/{serviceType}/{instanceId}` | 更新现有实例配置 |
| 删除实例 | DELETE | `/api/config/instance/{serviceType}/{instanceId}` | 删除指定实例 |
| 查看实例级限流 | GET | `/api/config/instance/{serviceType}/{instanceId}/rate-limit` | 读取实例限流配置 |
| 更新实例级限流 | PUT | `/api/config/instance/{serviceType}/{instanceId}/rate-limit` | 更新实例限流配置 |
| 查看实例级熔断 | GET | `/api/config/instance/{serviceType}/{instanceId}/circuit-breaker` | 读取实例熔断状态 |
| 更新实例级熔断 | PUT | `/api/config/instance/{serviceType}/{instanceId}/circuit-breaker` | 更新实例熔断配置 |
| 重置实例熔断 | POST | `/api/config/instance/{serviceType}/{instanceId}/circuit-breaker/reset` | 重置指定实例熔断器 |
| 查看全部熔断状态 | GET | `/api/config/instance/circuit-breaker/states` | 所有实例熔断器状态 |

> 注意：旧文档中的 `/api/config/instance/type/...`、`/info/...`、`/add/...`、`/update/...`、`/del/...` 路径已不存在；当前版本统一为 RESTful 风格的 `/{serviceType}` 与 `/{serviceType}/{instanceId}`。

### 1. 获取实例列表

```bash
# Get all instances of Chat service
curl -X GET "http://localhost:8080/api/config/instance/chat"
```

### 2. 添加实例

```bash
# Add new Chat service instance
curl -X POST "http://localhost:8080/api/config/instance/chat" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "qwen2:7b",
    "baseUrl": "http://gpu-server:8080",
    "path": "/v1/chat/completions",
    "weight": 2,
    "headers": {
      "Authorization": "Bearer your-token"
    }
  }'
```

### 3. 更新实例

```bash
# Update existing instance configuration
curl -X PUT "http://localhost:8080/api/config/instance/chat/qwen2:7b@http://gpu-server:8080" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "qwen2:7b",
    "baseUrl": "http://gpu-server:8080",
    "path": "/v1/chat/completions",
    "weight": 3
  }'
```

### 4. 删除实例

```bash
# Delete specified instance
curl -X DELETE "http://localhost:8080/api/config/instance/chat/qwen2:7b@http://gpu-server:8080"
```

## 持久化配置结构

动态配置以 JSON 形式持久化到 StoreManager（默认 JPA/H2）。实例字段以 `ModelInstance` 为准：`name`、`baseUrl`、`path`、`weight`、`instanceId`、`adapter`、`headers`、`tags`、`status`、`rateLimit`。服务级配置包含 `loadBalance`、`rateLimit`、`circuitBreaker`、`fallback` 等块。

```json
{
  "services": {
    "chat": {
      "loadBalance": { "type": "round-robin", "hashAlgorithm": "md5" },
      "rateLimit": {
        "enabled": true,
        "algorithm": "token-bucket",
        "capacity": 100,
        "rate": 10,
        "clientIpEnable": true
      },
      "circuitBreaker": {
        "enabled": true,
        "failureThreshold": 5,
        "timeout": 60000,
        "successThreshold": 2
      },
      "fallback": { "enabled": true, "strategy": "default" },
      "instances": [
        {
          "name": "llama3.2:3b",
          "baseUrl": "http://localhost:11434",
          "path": "/v1/chat/completions",
          "weight": 1,
          "headers": { "Authorization": "Bearer token" }
        }
      ]
    }
  }
}
```

> 实例对象**没有** `timeout` / `maxRetries` 字段；熔断器**没有** `recoveryTimeout` 字段（恢复等待时间由 `timeout` 键承担）。早期文档示例中的这些键已废弃。


> **配置历史与回滚**：当前版本没有 `/api/config/merge/*` 系列端点。配置版本列表、对比、应用（回滚）请使用 `/api/config/version/*` API，详见 [版本管理](version-management.md)。

## 实际使用场景

### 场景 1：添加新的 AI 服务实例

```bash
# 1. Add new high-performance GPU instance
curl -X POST "http://localhost:8080/api/config/instance/chat" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "llama3.1:70b",
    "baseUrl": "http://gpu-cluster:8080",
    "path": "/v1/chat/completions",
    "weight": 5
  }'

# 2. Verify instance addition success
curl -X GET "http://localhost:8080/api/config/instance/chat"

# 3. Test new instance
curl -X POST "http://localhost:8080/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama3.1:70b",
    "messages": [{"role": "user", "content": "Hello"}]
  }'
```

### 场景 2：动态调整负载均衡权重

```bash
# 1. List instances to find the instanceId
curl -X GET "http://localhost:8080/api/config/instance/chat"

# 2. Update instance weight (from 2 to 4)
curl -X PUT "http://localhost:8080/api/config/instance/chat/qwen2:7b@http://gpu-server:8080" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "qwen2:7b",
    "baseUrl": "http://gpu-server:8080",
    "path": "/v1/chat/completions",
    "weight": 4
  }'

# 3. Verify weight update
curl -X GET "http://localhost:8080/api/config/instance/chat"
```

### 场景 3：故障实例处理

```bash
# 1. Check instance health status
curl -X GET "http://localhost:8080/api/config/instance/chat"

# 2. Temporarily remove faulty instance
curl -X DELETE "http://localhost:8080/api/config/instance/chat/faulty-model@http://faulty-server:8080"

# 3. Add alternative instance
curl -X POST "http://localhost:8080/api/config/instance/chat" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "backup-model",
    "baseUrl": "http://backup-server:8080",
    "path": "/v1/chat/completions",
    "weight": 1
  }'
```

### 场景 4：配置版本管理

配置变更前自动产生版本记录；如需回滚到历史版本，使用版本管理 API：

```bash
# 1. List configuration versions
curl -X GET "http://localhost:8080/api/config/version"

# 2. Compare two versions
curl -X GET "http://localhost:8080/api/config/version/compare/1/2"

# 3. Apply a historical version
curl -X POST "http://localhost:8080/api/config/version/apply/2"
```

## 最佳实践

### 1. 配置变更流程

1. **变更前备份**：始终在变更前备份配置
2. **小步快跑**：一次只变更一个配置项
3. **验证测试**：变更后立即验证功能
4. **监控观察**：观察变更后的系统表现
5. **文档记录**：记录变更原因和结果

### 2. 实例管理策略

```bash
# Progressive Instance Replacement
# 1. Add new instance (smaller weight)
curl -X POST "http://localhost:8080/api/config/instance/chat" \
  -d '{"name": "new-model", "weight": 1}'

# 2. Observe new instance performance
# Monitor metrics, error rate, response time

# 3. Gradually increase new instance weight
curl -X PUT "http://localhost:8080/api/config/instance/chat/new-model@http://new-server:8080" \
  -d '{"name": "new-model", "baseUrl": "http://new-server:8080", "weight": 3}'

# 4. Gradually decrease old instance weight
curl -X PUT "http://localhost:8080/api/config/instance/chat/old-model@http://old-server:8080" \
  -d '{"name": "old-model", "baseUrl": "http://old-server:8080", "weight": 1}'

# 5. Remove old instance
curl -X DELETE "http://localhost:8080/api/config/instance/del/chat?..."
```

### 3. 配置监控

```bash
# Regularly check configuration status
curl -X GET "http://localhost:8080/api/config/merge/status"

# Validate configuration integrity
curl -X GET "http://localhost:8080/api/config/merge/validate"

# Monitor instance health status
curl -X GET "http://localhost:8080/actuator/health"
```

### 4. 错误处理

```bash
# Configuration rollback script example
#!/bin/bash

# Backup current configuration
BACKUP_RESULT=$(curl -s -X POST "http://localhost:8080/api/config/merge/backup")

if [[ $? -eq 0 ]]; then
    echo "Configuration backup successful"
    
    # Execute configuration change
    # ... configuration change operations ...
    
    # Verify change result
    HEALTH_CHECK=$(curl -s "http://localhost:8080/actuator/health")
    
    if [[ $(echo $HEALTH_CHECK | jq -r '.status') != "UP" ]]; then
        echo "Health check failed, starting rollback"
        # Execute rollback operation
        # ... rollback logic ...
    fi
else
    echo "Configuration backup failed, canceling change"
    exit 1
fi
```

## 故障排查

### 常见问题

1. **配置不生效**
    - 检查 API 响应是否成功
    - 验证配置文件是否正确保存
    - 确认服务实例是否健康

2. **实例添加失败**
    - 检查网络连通性
    - 验证 URL 格式是否正确
    - 确认后端服务是否可用

3. **配置合并失败**
    - 检查配置文件格式是否正确
    - 验证磁盘空间是否充足
    - 确认文件权限是否正确

### 调试命令

```bash
# View detailed error information
curl -v "http://localhost:8080/api/config/instance/add/chat" \
  -H "Content-Type: application/json" \
  -d '{"name": "test", ...}'

# Check service logs
docker logs trunkline

# Validate configuration file
cat config/model-router-config@1.json | jq .
```

## 下一步

完成动态配置学习后，您可以继续了解：

- **[负载均衡配置](load-balancing.md)** - 配置负载均衡策略
- **[限流配置](rate-limiting.md)** - 设置流量控制
- **[熔断器配置](circuit-breaker.md)** - 配置故障保护
- **[监控指南](../monitoring/index.md)** - 设置监控和告警
