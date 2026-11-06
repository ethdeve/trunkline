# Dynamic Configuration

<!-- 版本信息 -->
> **Doc Version**: 1.0.2  
> **Last Updated**: 2026-05-21
> **Git Commit**: 61384b4a  
> **Author**: Lincoln
<!-- /版本信息 -->

Trunkline provides flexible configuration options to meet various deployment scenarios. This guide covers all aspects of configuration from basic setup to advanced features.

## Modular Configuration Overview

Starting from v1.0.0, Trunkline adopts a modular configuration structure:

- Main configuration file: `application.yml`
- Base configuration modules: Files in the `config/base/` directory
- Feature configuration modules: Files in the `config/security/`, `config/tracing/` directories, etc.
- Environment configuration files: `application-dev.yml`, `application-prod.yml`, etc.

Although the configuration has been modularized, the dynamic configuration API can still be used to update instance configurations at runtime without affecting the modular structure.

## Configuration Overview
**1. Configuration Merging**: At startup, configuration documents in the config directory are read and automatically merged with application.yml configuration for dynamic updates.
**2. Instance Management API**: Update at runtime via REST API

## Configuration Merge & Persistence

At startup, YAML module configs and runtime-persisted configs (StoreManager, default JPA/H2) are merged: YAML provides defaults; admin edits are persisted and override YAML. Dynamic config is stored as JSON; its structure is described in the next section "Persisted Configuration Structure".

> The multi-version file directory `model-router-config@<version>.json` and the `/api/config/merge/*` endpoints (scan/preview/execute/backup/batch/cleanup/validate/statistics/status) described in earlier versions **do not exist** in the current release. For config history and rollback, use the `/api/config/version/*` API — see [Version Management](version-management.md).

## Instance Management API

### API Endpoint Overview

| Operation | Method | Path | Description |
|-----------|--------|------|-------------|
| Get Instance List | GET | `/api/config/instance/{serviceType}` | Get all instances of a specified service |
| Get Instance Details | GET | `/api/config/instance/{serviceType}` | Get detailed information of a single instance |
| Add Instance | POST | `/api/config/instance/{serviceType}` | Add a new service instance |
| Update Instance | PUT | `/api/config/instance/{serviceType}/{instanceId}` | Update existing instance configuration |
| Delete Instance | DELETE | `/api/config/instance/{serviceType}/{instanceId}` | Delete specified instance |

### 1. Get Instance List

```bash
# Get all instances of Chat service
curl -X GET "http://localhost:8080/api/config/instance/chat"

# Response Example
{
  "success": true,
  "data": [
    {
      "instanceId": "llama3.2:3b@http://localhost:11434",
      "name": "llama3.2:3b",
      "baseUrl": "http://localhost:11434",
      "path": "/v1/chat/completions",
      "weight": 1,
      "status": "HEALTHY"
    }
  ]
}
```

### 2. Add Instance

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

# Response Example
{
  "success": true,
  "message": "Instance added successfully",
  "data": {
    "instanceId": "qwen2:7b@http://gpu-server:8080"
  }
}
```

### 3. Update Instance

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

# Response Example
{
  "success": true,
  "message": "Instance updated successfully"
}
```

### 4. Delete Instance

```bash
# Delete specified instance
curl -X DELETE "http://localhost:8080/api/config/instance/chat/qwen2:7b@http://gpu-server:8080"

# Response Example
{
  "success": true,
  "message": "Instance deleted successfully"
}
```

## Persisted Configuration Structure

Dynamic config is persisted as JSON to StoreManager (default JPA/H2). Instance fields follow `ModelInstance`: `name`, `baseUrl`, `path`, `weight`, `instanceId`, `adapter`, `headers`, `tags`, `status`, `rateLimit`. Service-level config contains `loadBalance`, `rateLimit`, `circuitBreaker`, `fallback` blocks.

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

> Instance objects have **no** `timeout` / `maxRetries` fields; the circuit breaker has **no** `recoveryTimeout` field (the recovery wait time is the `timeout` key). These keys in earlier doc examples are deprecated.


> **Configuration history & rollback**: the `/api/config/merge/*` endpoints (scan/preview/execute/backup/batch/cleanup/validate/statistics/status) do not exist in this version. Use `/api/config/version/*` for version list, diff and apply (rollback) — see [Version Management](version-management.md).

## Practical Usage Scenarios

### Scenario 1: Adding New AI Service Instance

```bash
# 1. Add new high-performance GPU instance
curl -X POST "http://localhost:8080/api/config/instance/chat" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "llama3.1:70b",
    "baseUrl": "http://gpu-cluster:8080",
    "path": "/v1/chat/completions",
    "weight": 5,
    "timeout": 60000
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

### Scenario 2: Dynamically Adjust Load Balancing Weight

```bash
# 1. Get current instance list
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

### Scenario 3: Fault Instance Handling

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

### Scenario 4: Configuration Version Management

Config changes automatically produce version records. To roll back to a historical version, use the version management API:

```bash
# 1. List configuration versions
curl -X GET "http://localhost:8080/api/config/version"

# 2. Compare two versions
curl -X GET "http://localhost:8080/api/config/version/compare/1/2"

# 3. Apply a historical version
curl -X POST "http://localhost:8080/api/config/version/apply/2"
```

## Best Practices

### 1. Configuration Change Process

1. **Backup Before Changes**: Always backup configuration before making changes
2. **Small Steps**: Change only one configuration item at a time
3. **Validation Testing**: Immediately verify functionality after changes
4. **Monitoring Observation**: Observe system performance after changes
5. **Documentation Recording**: Record change reasons and results

### 2. Instance Management Strategy

```bash
# Progressive Instance Replacement
# 1. Add new instance (smaller weight)
curl -X POST "http://localhost:8080/api/config/instance/chat" \
  -d '{"name": "new-model", "weight": 1, ...}'

# 2. Observe new instance performance
# Monitor metrics, error rate, response time

# 3. Gradually increase new instance weight
curl -X PUT "http://localhost:8080/api/config/instance/chat/new-model@http://new-server:8080" \
  -d '{"name": "new-model", "baseUrl": "http://new-server:8080", "weight": 3}'

# 4. Gradually decrease old instance weight
curl -X PUT "http://localhost:8080/api/config/instance/chat/old-model@http://old-server:8080" \
  -d '{"name": "old-model", "baseUrl": "http://old-server:8080", "weight": 1}'

# 5. Remove old instance
curl -X DELETE "http://localhost:8080/api/config/instance/chat/old-model@http://old-server:8080"
```

### 3. Configuration Monitoring

```bash
# Check instance circuit breaker states
curl -X GET "http://localhost:8080/api/config/instance/circuit-breaker/states"

# View config sources
curl -X GET "http://localhost:8080/api/config/sources"

# Monitor instance health status
curl -X GET "http://localhost:8080/actuator/health"
```

### 4. Error Handling

```bash
# Configuration rollback script example
#!/bin/bash

# Record current version before change
CURRENT_VERSION=$(curl -s "http://localhost:8080/api/config/version" | jq -r '.[0].version')

# Execute configuration change
# ... configuration change operations ...

# Verify change result
HEALTH_CHECK=$(curl -s "http://localhost:8080/actuator/health")

if [[ $(echo $HEALTH_CHECK | jq -r '.status') != "UP" ]]; then
    echo "Health check failed, rolling back to version $CURRENT_VERSION"
    curl -X POST "http://localhost:8080/api/config/version/apply/$CURRENT_VERSION"
fi
```

## Troubleshooting

### Common Issues

1. **Configuration Not Taking Effect**
    - Check if API response is successful
    - Verify if configuration file is correctly saved
    - Confirm if service instance is healthy

2. **Instance Addition Failed**
    - Check network connectivity
    - Verify if URL format is correct
    - Confirm if backend service is available

3. **Configuration Not Taking Effect**
    - Check config sources: `GET /api/config/sources`
    - Verify the persisted config was saved
    - Confirm the service instance is healthy

### Debugging Commands

```bash
# View detailed error information
curl -v "http://localhost:8080/api/config/instance/chat" \
  -H "Content-Type: application/json" \
  -d '{"name": "test", "weight": 1}'

# Check service logs
docker logs trunkline

# List config versions
curl -X GET "http://localhost:8080/api/config/version"
```

## Next Steps

After completing dynamic configuration learning, you can continue to learn about:

- **[Load Balancing Configuration](load-balancing.md)** - Configure load balancing strategies
- **[Rate Limiting Configuration](rate-limiting.md)** - Set traffic control
- **[Circuit Breaker Configuration](circuit-breaker.md)** - Configure fault protection
- **[Monitoring Guide](../monitoring/index.md)** - Set up monitoring and alerts
