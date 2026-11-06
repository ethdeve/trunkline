# Configuration Validation Rules

<!-- 版本信息 -->
> **Doc Version**: 1.1.0  
> **Last Updated**: 2026-10-28
> **Author**: Lincoln
<!-- /版本信息 -->

Trunkline validates sensitive configuration and integrity settings at startup and via the admin layer, and exposes the validation rules and config sources through a management API.

## Management API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/config/sources` | GET | Where each config key was loaded from (YAML / env var / dynamic config) |
| `/api/config/validation-rules` | GET | Validation rule definitions |
| `/api/config/environment-variables` | GET | Required/optional environment variable guide |

```bash
# View validation rule definitions
curl -X GET "http://localhost:8080/api/config/validation-rules"

# View the environment variable guide
curl -X GET "http://localhost:8080/api/config/environment-variables"

# View config source tracking
curl -X GET "http://localhost:8080/api/config/sources"
```

## Rule Categories

Rules fall into two categories (per `ConfigValidationController`):

### 1. Sensitive Config Rules (sensitiveConfig)

Validate that sensitive settings are injected via environment variables and meet minimum length:

| Rule | Description |
|------|-------------|
| `JWT_SECRET` | JWT signing secret, minimum 32 chars, must be injected via env var `JWT_SECRET` |
| `INITIAL_ADMIN_PASSWORD` | Initial admin password, minimum length 8, must be injected via env var |
| `GPUSTACK_API_TOKEN` | GPUStack API Token (required when using the GPUStack adapter) |

A validation failure logs an error at startup and blocks startup (or marks the setting as missing).

### 2. Integrity Config Rules (integrityConfig)

Validate that key business settings are within legal ranges:

| Setting | Legal Range | Default |
|---------|-------------|---------|
| `server.port` | 1 - 65535 | 8080 |
| `model.load-balance.type` | enum: `round-robin` / `weighted` / `least-connections` / `ip-hash` / `random` | `round-robin` (note: the runtime actually supports `random`/`round-robin`/`least-connections`/`ip-hash`/`consistent-hash`/`latency`; the enum is governed by the controller's rule table) |
| `model.circuit-breaker.enabled` | boolean | true |
| `model.circuit-breaker.failureThreshold` | minimum 1 | 5 |
| `model.circuit-breaker.timeoutMs` | 100 - 300000 | 60000 |
| `store.type` | enum: `jpa` / `redis` / `memory` | `jpa` |

## Required Environment Variables

Required at startup (missing triggers a warning or blocks startup):

| Environment Variable | Description | Min Length |
|----------------------|-------------|-----------|
| `JWT_SECRET` | JWT signing secret | 32 |
| `INITIAL_ADMIN_PASSWORD` | Initial admin password | 8 |

## Optional Environment Variables

| Environment Variable | Default | Description |
|----------------------|---------|-------------|
| `GPUSTACK_API_TOKEN` | - | GPUStack adapter API Token |
| `JWT_EXPIRATION_MINUTES` | 60 | Access token lifetime (minutes) |
| `JWT_REFRESH_EXPIRATION_DAYS` | 7 | Refresh token lifetime (days) |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | localhost/6379/empty | Redis connection (used for distributed rate limit, state persistence Tier1, Redis store) |
| `SERVER_PORT` | 8080 | Server port |
| `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` | H2 defaults | Switch to PostgreSQL |

> **To be confirmed**: earlier versions of this doc described a "startup validation banner + 7 rules" (`RATE_LIMIT_CAPACITY` / `RATE_LIMIT_RATE` / `RATE_LIMIT_SCOPE` / `CIRCUIT_BREAKER_FAILURE_THRESHOLD` / `CIRCUIT_BREAKER_TIMEOUT` / `MODEL_SERVICES_INSTANCES` / `JWT_EXPIRATION`) and the corresponding `RATE_LIMIT_CAPACITY` env vars. No such implementation was found in the current codebase (`grep RATE_LIMIT_CAPACITY` returns zero matches). The authoritative validation is the rule table returned by `/api/config/validation-rules`.

## Relationship to Other Configuration

- Sensitive-config validation is the first line of deployment security, working with [JWT Persistence](jwt-persistence.md)
- Integrity validation covers the key parameters of [Load Balancing](load-balancing.md), [Circuit Breaker](circuit-breaker.md), and [Store Configuration](store-config.md)
- Config source tracking helps answer "why didn't my config take effect", working with [Dynamic Config](dynamic-config.md)

## Next Steps

- [Dynamic Config](dynamic-config.md) - runtime configuration management
- [JWT Persistence](jwt-persistence.md) - JWT tokens and audit configuration
- [Store Configuration](store-config.md) - storage types and migration
