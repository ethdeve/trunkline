# Configuration Guide

<!-- 版本信息 -->
> **Doc Version**: 1.1.0  
> **Last Updated**: 2026-10-28
> **Author**: Lincoln
<!-- /版本信息 -->

Trunkline provides flexible configuration options to meet various deployment scenarios. This guide covers all configuration aspects from basic setup to advanced features.

## Modular Configuration Overview

Since v2.8.x, Trunkline adopts a modular configuration structure, separating functional configurations into independent files to improve maintainability and readability.

### Configuration File Structure

```
src/main/resources/
├── application.yml                    # Main configuration file, imports other modules
├── config/
│   ├── common/                        # Shared infrastructure
│   │   ├── server.yml                 # Server configuration
│   │   ├── webclient.yml              # WebClient connection pool
│   │   ├── logging.yml                # Logging configuration
│   │   ├── scheduling.yml             # Scheduled tasks toggle
│   │   └── cluster.yml                # Cluster toggle
│   ├── config-service/
│   │   └── core.yml                   # Config service core (store/datasource/JPA)
│   ├── router/                        # Routing service
│   │   ├── adapter.yml                # Adapter configuration
│   │   ├── loadbalancer.yml           # Load balancing configuration
│   │   ├── ratelimit.yml              # Rate limiting configuration
│   │   ├── circuitbreaker.yml         # Circuit breaker configuration
│   │   ├── fallback.yml               # Fallback configuration
│   │   ├── services.yml               # Service instances
│   │   ├── rules.yml                  # Conditional routing rules
│   │   └── pools.yml                  # Model pools (resource pools)
│   ├── auth/                          # Authentication service
│   │   ├── jwt.yml                    # JWT authentication
│   │   ├── api-key.yml                # API key configuration
│   │   ├── audit.yml                  # Audit configuration
│   │   └── sanitization.yml           # Data sanitization
│   ├── monitor/                       # Monitoring service
│   │   ├── slow-query-alerts.yml
│   │   ├── error-tracking.yml
│   │   └── persistence-monitoring-base.yml
│   ├── tracing/
│   │   └── tracing-base.yml           # Distributed tracing
│   ├── persistence/
│   │   └── state-persistence-base.yml # State persistence (3-tier fallback)
│   ├── base/                          # Base config (backward compatible)
│   │   ├── server-base.yml
│   │   ├── model-services-base.yml
│   │   └── monitoring-base.yml
│   └── security/                      # Security config (backward compatible)
│       ├── security-base.yml
│       ├── persistence-base.yml
│       └── audit-base.yml
├── application-dev.yml                # Development profile
├── application-staging.yml            # Staging profile
├── application-prod.yml               # Production profile
├── application-legacy.yml             # Legacy compatibility profile
└── application-migrate.yml            # Data migration profile
```

### Configuration Import Mechanism

The main `application.yml` imports module configurations via `spring.config.import`:

```yaml
# application.yml
spring:
  config:
    import:
      # common module
      - classpath:config/common/server.yml
      - classpath:config/common/webclient.yml
      - classpath:config/common/logging.yml
      - classpath:config/common/scheduling.yml
      - classpath:config/common/cluster.yml
      # config-service module
      - classpath:config/config-service/core.yml
      # router module
      - classpath:config/router/adapter.yml
      - classpath:config/router/loadbalancer.yml
      - classpath:config/router/ratelimit.yml
      - classpath:config/router/circuitbreaker.yml
      - classpath:config/router/fallback.yml
      - classpath:config/router/services.yml
      - classpath:config/router/rules.yml
      - classpath:config/router/pools.yml
      # auth module
      - classpath:config/auth/jwt.yml
      - classpath:config/auth/api-key.yml
      - classpath:config/auth/audit.yml
      - classpath:config/auth/sanitization.yml
      # monitor / tracing / persistence modules
      - classpath:config/base/monitoring-base.yml
      - classpath:config/monitor/slow-query-alerts.yml
      - classpath:config/monitor/error-tracking.yml
      - classpath:config/monitor/persistence-monitoring-base.yml
      - classpath:config/tracing/tracing-base.yml
      - classpath:config/persistence/state-persistence-base.yml
      # backward compatibility
      - classpath:config/base/server-base.yml
      - classpath:config/base/model-services-base.yml
      - classpath:config/security/security-base.yml
      - classpath:config/security/persistence-base.yml
      - classpath:config/security/audit-base.yml
      # external config files (must be last; later imports win)
      - optional:file:/app/config/application.yml
      - optional:file:/app/config/auth/jwt.yml
      - optional:file:/app/config/router/services.yml
```

## Configuration Overview

Trunkline supports two main configuration styles:

1. **Static config**: defined in YAML files, loaded at startup
2. **Dynamic config**: updated at runtime via REST API (instances, rate limit, circuit breaker, rules, pools, etc.)

## Configuration Hierarchy

Loaded in this order (later sources override earlier ones):

1. Default configuration (bundled in the JAR)
2. `application.yml` (classpath) and its `spring.config.import` modules
3. `./application.yml` (current directory)
4. `./config/application.yml` (config directory)
5. Environment variables
6. Command-line arguments
7. Dynamic configuration (runtime updates, persisted to StoreManager)

## Basic Structure

```yaml
server:
  port: 8080

model:
  services:
    <serviceType>:
      load-balance:
        type: <strategy>
      rate-limit:
        algorithm: <algorithm>
        # ... rate limit settings
      circuit-breaker:
        enabled: true
        # ... circuit breaker settings
      fallback:
        strategy: <fallback type>
        # ... fallback settings
      instances:
        - name: <model name>
          base-url: <service URL>
          path: <API path>
          weight: <load balance weight>
          # ... instance settings

store:
  type: jpa            # default jpa
  path: ./config       # config import/export directory
  fallback-storage: memory
```

## Service Types

| Service Type | Description | Default Path |
|--------------|-------------|--------------|
| `chat` | Chat completions | `/v1/chat/completions` |
| `embedding` | Text embeddings | `/v1/embeddings` |
| `rerank` | Reranking | `/v1/rerank` |
| `tts` | Text-to-speech | `/v1/audio/speech` |
| `stt` | Speech-to-text | `/v1/audio/transcriptions` |
| `imgGen` | Image generation | `/v1/images/generations` |
| `imgEdit` | Image editing | `/v1/images/edits` |

## Configuration Sections

### 1. Load Balancing

Distributes requests across service instances (see `config/router/loadbalancer.yml`):

```yaml
model:
  load-balance:
    type: round-robin        # default random
    hash-algorithm: md5      # hash algorithm for ip-hash / consistent-hash
    virtual-nodes: 150       # virtual nodes for consistent hash
    ewma-alpha: 0.2          # EWMA smoothing factor for latency strategy
```

**Available strategies:**

- **random**: pick a random instance (default)
- **round-robin**: poll instances in order
- **least-connections**: route to the instance with the fewest active connections
- **ip-hash**: client-IP hashing for session stickiness
- **consistent-hash**: consistent hashing with virtual nodes (small impact on membership changes)
- **latency**: EWMA latency-weighted random (lower latency = higher weight)

### 2. Rate Limiting

Controls request rate to protect backends (see `config/router/ratelimit.yml`):

```yaml
model:
  rate-limit:
    enabled: false
    algorithm: token-bucket   # default token-bucket
    capacity: 1000
    rate: 100
    scope: service            # service / instance / global
    client-ip-enable: false   # enable per-client-IP limiting
    key-dimension: client-ip  # client-ip / api-key / tenant
```

**Available algorithms (per `ComponentFactory`):**

- **token-bucket**: allows bursts up to capacity (default)
- **leaky-bucket**: smooth constant-rate output
- **sliding-window**: precise time-window control
- **warm-up**: ramps up the rate after startup

### 3. Circuit Breaker

Prevents cascading failures (see `config/router/circuitbreaker.yml`):

```yaml
model:
  circuit-breaker:
    enabled: true
    failureThreshold: 5      # failures before opening
    timeout: 60000           # OPEN duration (ms) before HALF_OPEN
    successThreshold: 2      # successes in HALF_OPEN to close
```

### 4. Fallback Strategy

Defines behavior when a service is unavailable (see `config/router/fallback.yml`):

```yaml
model:
  fallback:
    enabled: true
    strategy: default        # default / cache / mock
```

**Available strategies:**

- **default**: return a predefined fallback response
- **cache**: return the last successful cached response (rerank fallback cache defaults `cache-size: 100`, `cache-ttl: 600000` ms)
- **mock**: return a mock response

### 5. Service Instances

Defines actual endpoints (see `config/router/services.yml`):

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

**Instance fields:**

- `name`: model identifier
- `base-url`: service base URL (kebab-case recommended in YAML)
- `path`: API endpoint path
- `weight`: load balance weight (higher = more traffic)
- `instance-id`: unique instance id (defaults to `name@baseUrl`)
- `adapter`: instance-level adapter (falls back to service/global adapter)
- `headers`: custom request headers
- `tags`: instance tags (key-value pairs for tag-based routing)

### 6. Store Configuration

Configures persistence of dynamic configuration (see `config/config-service/core.yml`):

```yaml
store:
  type: jpa                 # default jpa (H2), alternatives redis / memory
  path: ./config            # config import/export directory
  fallback-storage: memory  # fallback storage on degradation
  migration:
    enabled: true           # migrate from legacy file store to JPA
  security-migration:
    enabled: true           # migrate security data (API keys/JWT accounts)
```

The default datasource is H2 (`spring.datasource.url: jdbc:h2:file:./data/trunkline`). Use `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` to switch to PostgreSQL.

## Environment-Specific Configuration

- **Development**: `application-dev.yml`
- **Staging**: `application-staging.yml`
- **Production**: `application-prod.yml`
- **Legacy compatibility**: `application-legacy.yml`
- **Data migration**: `application-migrate.yml`

Profile files only contain the deltas, following Spring Boot's override mechanism.

## Environment Variables

```bash
# Required: JWT secret (>= 32 chars) and initial admin password
export JWT_SECRET="your-secret-at-least-32-chars"
export INITIAL_ADMIN_PASSWORD="your-admin-password"

# Server port
export SERVER_PORT=8080

# Model service config (Spring Boot relaxed binding)
export MODEL_LOAD_BALANCE_TYPE=round-robin
export MODEL_RATE_LIMIT_ALGORITHM=token-bucket

# Redis (if distributed counters / state persistence Tier1 enabled)
export REDIS_HOST=localhost
export REDIS_PORT=6379
export REDIS_PASSWORD=""
```

## Command-Line Arguments

```bash
java -jar trunkline.jar \
  --server.port=8080 \
  --model.load-balance.type=round-robin \
  --spring.profiles.active=prod
```

## Configuration Validation

Trunkline validates sensitive configuration and key integrity settings at startup. The admin API exposes:

- `GET /api/config/sources`: where each config key was loaded from
- `GET /api/config/validation-rules`: validation rule definitions (sensitive secret min length, LB strategy enum, circuit breaker threshold ranges, etc.)
- `GET /api/config/environment-variables`: required/optional environment variable guide

See [Validation Rules](validation-rules.md).

## Best Practices

### 1. Use Descriptive Names

```yaml
model:
  services:
    chat:
      instances:
        - name: "qwen2.5-7b-fast"
          base-url: "http://fast-gpu-server:11434"
        - name: "qwen2.5-14b-accurate"
          base-url: "http://high-memory-server:11434"
```

### 2. Use Weights for Progressive Rollout

```yaml
model:
  services:
    chat:
      instances:
        - name: "stable-model-v1"
          base-url: "http://stable-server:11434"
          weight: 9  # 90% traffic
        - name: "new-model-v2"
          base-url: "http://new-server:11434"
          weight: 1  # 10% traffic
```

### 3. Keep Secrets in Environment Variables

JWT secret, admin password, GPUStack token and Redis password must be injected via environment variables, never hard-coded in YAML.

## Next Steps

- [Application Config](application-config.md) - detailed application settings
- [Dynamic Config](dynamic-config.md) - runtime configuration management
- [Load Balancing](load-balancing.md) - load balancing strategies
- [Rate Limiting](rate-limiting.md) - rate limiting algorithms
- [Circuit Breaker](circuit-breaker.md) - circuit breaker configuration
- [Store Configuration](store-config.md) - storage and data migration
- [Model Pools](model-pools.md) - model pool configuration and pool-level quotas
- [Version Management](version-management.md) - config version apply vs rollback
- [KV Cache](kv-cache.md) - prefix-cache-friendly gateway: sticky routing & cache metrics
- [Response Cache](response-cache.md) - full response cache: deterministic requests reuse cached responses and skip upstream
- [Quota Ledger](quota.md) - multi-dimensional quota ledger
