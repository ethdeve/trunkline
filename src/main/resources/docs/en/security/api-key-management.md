# API Key Management Guide

<!-- Version Information -->
> **Version**: 1.1.1
> **Last Updated**: 2026-09-15
> **Git Commit**: fb0cd62f
> **Author**: Lincoln
<!-- /Version Information -->



## Overview

The API Key authentication feature of Trunkline provides a secure access control mechanism. Through API Keys you control who can access your AI model services.

> **v3.5 governance (important)**: the only outbound entry is the **model group (virtual model name)**. Keys no longer register upstream instance names (the former `allowedModels` is removed; instances cannot be called directly). Admission goes through a chain of three gates: **model category → model group whitelist → runtime constraints**.

## Three-gate permission model

A request passes only if it crosses all three gates in order; any failure rejects it:

| Gate | Field | Meaning | On failure |
|------|-------|---------|------------|
| **Gate 1 · model category** | `permissions` | Which model categories the key may call. The request category C is derived from the URL endpoint and must be in permissions | 403 |
| **Gate 2 · model group whitelist** | `allowedGroups` | Which model groups (pool names / virtual model names) the key may use. Empty = unrestricted; a group's category must be within permissions | 403 / 404 |
| **Gate 3 · runtime constraints** | `allowedIpAddresses` / `expiresAt` / quota & alerts | Source IP, expiry, multi-window quotas and alert thresholds | 403 / 429 |

There are exactly 7 model categories (`org.unreal.modelrouter.common.constants.ServiceTypeConstants`):

| Value | Meaning |
|-------|---------|
| `chat` | Chat model |
| `embedding` | Embedding model |
| `rerank` | Rerank model |
| `tts` | Text-to-speech |
| `stt` | Speech-to-text |
| `imgGen` | Image generation |
| `imgEdit` | Image edit |

> The legacy permission value `admin` (case-insensitive) allows all categories at runtime and skips gate-2 consistency validation.

### Gate-2 consistency validation

On save, `ApiKeyWhitelistValidator` checks that every group name in `allowedGroups` belongs to a category within the key's `permissions`; otherwise it returns `400 INVALID_REQUEST`.

| permissions | allowedGroups | Runtime result |
|-------------|---------------|----------------|
| `chat` | `xxx-chat` | OK |
| `tts` | `xxx-chat` | Gate 1 → 403 |
| `chat` | `xxx-tts` | Gate 1 passes, but no pool matches the category → 404/503 |

> Names not found in the catalog (deleted or external) or catalog-read failures are **conservatively allowed** (warned, not blocked).

### Key-to-group binding semantics

- Set `allowedGroups` to allowed **group (pool) names**, never instance names;
- Clients pass a group name as the `model` parameter; the gateway picks an instance inside the group;
- The quick-start wizard (`/quick-start`) auto-creates groups and writes them into the test key's `allowedGroups`, with `permissions` = imported categories.

## Features

### Core Features

- **Three-gate access control**: model category (permissions) → model group whitelist (allowedGroups) → IP/expiry/quota & alerts
- **Expiration Time Management**: Supports setting expiration times for API Keys
- **Usage Statistics**: Records usage statistics for each API Key
- **Quota Management**: Multi-window (hourly/daily/monthly) request & token limits, per-minute rate, and alert thresholds
- **Cache Optimization**: Supports Redis and local caching to improve authentication performance
- **Dynamic Management**: Supports adding, deleting, and updating API Keys at runtime (admin-side `hasRole('ADMIN')`)
- **Persistent Storage**: Supports storing API Key information in H2 database
- **H2 Database Default Storage**: H2 is the default persistent storage for API Key data

### Security Enhancements (v1.6.1+)

- **SHA-256 hashed storage**: API keys are stored as SHA-256 hashes (with salt), not in plaintext
- **Admin API rate limiting**: management endpoints are rate-limited (write 30/min, 200/hour; create 10/hour)
- **IP whitelist**: restrict a key to specific source IPs / CIDR ranges
- **Daily request limit**: cap the number of requests per day per key

### Advanced Management (v1.6.2+)

- **Bulk import/export**: export and import API keys for migration and backup
- **Automatic key rotation**: configure a rotation period to issue new key values periodically
- **Expired-key auto cleanup**: automatically disable expired API keys
- **Creator audit**: record who created a key and from which IP

## Quick Start

### 1. Enable API Key Authentication

Enable security features in `application.yml`:

```yaml
jairouter:
  security:
    enabled: true
    api-key:
      enabled: true
      header-name: "X-API-Key"
```

### 2. Configure API Key

```yaml
jairouter:
  security:
    api-key:
      keys:
        - key-id: "admin-key-001"
          key-value: "${ADMIN_API_KEY}"
          description: "Administrator API Key"
          permissions: ["admin"]                 # Gate 1: model category; admin = legacy, all categories
          allowed-groups: ["OpenAI-chat"]        # Gate 2: allowed model groups (pool names); empty = unrestricted
          expires-at: "2027-12-31T23:59:59"
          enabled: true
```

### 3. Client Usage

Add the API Key to the HTTP request header:

```bash
curl -H "X-API-Key: your-api-key-here" \
     -X POST \
     -H "Content-Type: application/json" \
     -d '{"model": "gpt-3.5-turbo", "messages": [...]}' \
     http://localhost:8080/v1/chat/completions
```

## Detailed Configuration

### API Key Configuration Parameters

| Parameter | Type | Default Value | Description |
|-----------|------|---------------|-------------|
| `enabled` | boolean | true | Whether to enable API Key authentication |
| `header-name` | string | "X-API-Key" | Name of the API Key request header |
| `default-expiration-days` | int | 365 | Default number of days until expiration |
| `cache-enabled` | boolean | true | Whether to enable caching |
| `cache-expiration-seconds` | int | 3600 | Cache expiration time (in seconds) |

### API Key Attributes

Each API Key contains the following attributes:

```yaml
- key-id: "unique-key-identifier"      # Unique identifier
  key-value: "actual-api-key-string"   # Actual API Key value
  description: "Key description"        # Description information
  permissions: ["chat", "embedding"]   # Gate 1: callable model categories
  allowed-groups: ["OpenAI-chat", "OpenAI-embedding"]  # Gate 2: model group whitelist (pool names)
  allowed-ip-addresses: ["10.0.0.0/8"] # Gate 3: source IP whitelist (optional)
  expires-at: "2027-12-31T23:59:59"    # Expiration time
  enabled: true                         # Whether enabled
  daily-token-limit: 100000            # Daily Token usage limit (optional)
  rate-limit-per-minute: 100           # Requests per minute limit (optional)
  quota-alert-threshold: 80           # Alert threshold percentage (optional)
  metadata:                            # Metadata
    created-by: "admin"
    department: "IT"
```

### Quota Management Configuration

Trunkline supports comprehensive quota management for API Keys, including daily Token usage limits, rate limiting, and alert thresholds.

#### Quota Configuration Parameters

| Parameter | Type | Default Value | Description |
|-----------|------|---------------|-------------|
| `dailyRequestLimit` | long | 0 (unlimited) | Max requests per day |
| `dailyTokenLimit` | long | 0 (unlimited) | Max Token usage per day |
| `hourlyRequestLimit` | long | 0 (unlimited) | Max requests per hour (multi-window quota) |
| `hourlyTokenLimit` | long | 0 (unlimited) | Max Token usage per hour |
| `monthlyRequestLimit` | long | 0 (unlimited) | Max requests per month |
| `monthlyTokenLimit` | long | 0 (unlimited) | Max Token usage per month |
| `rateLimitPerMinute` | int | 0 (unlimited) | Max requests per minute |
| `quotaAlertThreshold` | double | 0.8 | Alert threshold as ratio 0.0–1.0 (0.8 = 80%) |

#### Quota Configuration Example

```yaml
jairouter:
  security:
    api-key:
      keys:
        - key-id: "limited-api-key"
          key-value: "${LIMITED_API_KEY}"
          description: "API Key with quota limits"
          permissions: ["chat"]
          allowed-groups: ["OpenAI-chat"]
          expires-at: "2027-12-31T23:59:59"
          enabled: true
          daily-token-limit: 100000      # 100K tokens per day
          rate-limit-per-minute: 60      # 60 requests per minute
          quota-alert-threshold: 0.8      # Alert at 80% usage (0.0–1.0)
```

#### How Quota Management Works

1. **Daily Token Limit**: Tracks Token usage per day. When the limit is reached, API requests are rejected with a 429 (Too Many Requests) status code.

2. **Rate Limiting**: Uses a sliding window algorithm to limit requests per minute. When the limit is reached, API requests are rejected with a 429 status code.

3. **Alert Threshold**: Ratio `0.0–1.0` (0.8 = 80%). `0` on limit fields means unlimited.

4. **Console (v3.2.0)**: Security → API Keys → **Quota** drawer (presets, save, reset). Management APIs: `PUT/GET /auth/api-keys/{id}/quota`, batch-reset, reset-all. See [management API](../api-reference/management-api.md).

3. **Alert Threshold**: When usage exceeds the threshold percentage (e.g., 80%), the system triggers an alert. This is useful for monitoring and proactive management.

4. **Automatic Reset**: Quota counters are automatically reset at midnight (00:00:00) every day.

#### Monitoring Quota Usage

You can monitor API Key quota usage through:

1. **Management API** (quota usage has no dedicated Prometheus metric):
   - `GET /api/auth/api-keys/{keyId}/quota`: per-key quota detail (today's requests/tokens, alert triggered)
   - `GET /api/auth/api-keys/quota/overview`: overview of all keys
   - `GET /api/auth/api-keys/quota/alerts`: keys that crossed the alert threshold

2. **Audit Logs**: Quota-related events are logged for audit purposes.

3. **Management API**: Query quota status through the management API endpoint.

### v3.2.0 Quick Quota Operations API

| Endpoint | Description |
|----------|-------------|
| `PUT /api/auth/api-keys/{keyId}/quota` | Update quota fields only (0 = unlimited; threshold 0.0–1.0) |
| `POST .../quota/batch-reset` | Batch reset (body `{keyIds:[]}`) |
| `POST .../quota/reset-all` | Reset all keys |
| `GET .../quota` | Detail including `remainingRequests` / `remainingTokens` (-1 when unlimited) |

Console: the **Quota** drawer in the action column of the API key list supports presets (unlimited / dev / standard / strict).

See [Management API](../api-reference/management-api.md).

### Gate 1: callable model categories (permissions)

`permissions` is the set of model categories the key may call (see the 7 categories above), not the legacy read/write/delete:

| Example | Meaning |
|---------|---------|
| `["chat"]` | Only the chat category |
| `["chat","embedding"]` | Chat and embedding |
| `["admin"]` (legacy) | All categories; skips gate-2 consistency validation |

> At runtime the request category is derived from the URL endpoint; a category outside `permissions` returns 403.

## Environment Variable Configuration

For security reasons, it is recommended to set API Keys through environment variables:

### Linux/macOS

```bash
# Production Environment API Key Configuration
export PROD_ADMIN_API_KEY="your-production-admin-api-key-here"
export PROD_SERVICE_API_KEY="your-production-service-api-key-here"
export PROD_READONLY_API_KEY="your-production-readonly-api-key-here"

# API Key Expiration Time Configuration
export PROD_ADMIN_KEY_EXPIRES="2027-12-31T23:59:59"
export PROD_SERVICE_KEY_EXPIRES="2027-12-31T23:59:59"
export PROD_READONLY_KEY_EXPIRES="2027-12-31T23:59:59"
```

### Windows

```cmd
# Production Environment API Key Configuration
set PROD_ADMIN_API_KEY=your-production-admin-api-key-here
set PROD_SERVICE_API_KEY=your-production-service-api-key-here
set PROD_READONLY_API_KEY=your-production-readonly-api-key-here

# API Key Expiration Time Configuration
set PROD_ADMIN_KEY_EXPIRES=2027-12-31T23:59:59
set PROD_SERVICE_KEY_EXPIRES=2027-12-31T23:59:59
set PROD_READONLY_KEY_EXPIRES=2027-12-31T23:59:59
```

### Docker

```bash
# Production Environment Docker Deployment
docker run -d \
  --name jairouter-prod \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e PROD_ADMIN_API_KEY="your-production-admin-api-key-here" \
  -e PROD_SERVICE_API_KEY="your-production-service-api-key-here" \
  -e PROD_READONLY_API_KEY="your-production-readonly-api-key-here" \
  -e PROD_JWT_SECRET="your-production-jwt-secret-here" \
  -e REDIS_HOST="your-redis-host" \
  -e REDIS_PORT="your-redis-port" \
  -e REDIS_PASSWORD="your-redis-password" \
  -v $(pwd)/config:/app/config:ro \
  -v $(pwd)/logs:/app/logs \
  sodlinken/jairouter:latest
```

## Cache Configuration

### Redis Cache

```yaml
jairouter:
  security:
    performance:
      cache:
        redis:
          enabled: true
          host: "localhost"
          port: 6379
          password: "your-redis-password"
          database: 0
```

### Local Cache

```yaml
jairouter:
  security:
    performance:
      cache:
        local:
          enabled: true
          api-key:
            max-size: 1000
            expire-after-write: 3600
```

## API Key Persistence Storage

### Enable API Key Persistence

Trunkline supports persistent storage of API Keys for enhanced management and monitoring:

```yaml
jairouter:
  security:
    api-key:
      # API Key persistence configuration
      persistence:
        enabled: true
        primary-storage: h2    # h2, redis, memory
        fallback-storage: memory  # memory
        
        # Cleanup configuration
        cleanup:
          enabled: true
          schedule: "0 0 3 * * ?"  # Daily at 3 AM
          retention-days: 365
          batch-size: 1000
        
        # Memory storage configuration
        memory:
          max-keys: 10000
          cleanup-threshold: 0.8  # 80% triggers cleanup
          lru-enabled: true
          
        # H2 database storage configuration
        h2:
          table-name: "api_keys"  # Table name
          max-batch-size: 1000    # Maximum batch size
```

### API Key Management Features

With persistence enabled, you can:

1. **Track API Keys**: Monitor all API Keys and their status
2. **Lifecycle Management**: Automatic status updates and cleanup
3. **Enhanced Security**: Persistent storage with H2 database support
4. **Audit Trail**: Complete audit logging of API Key operations
5. **Performance Monitoring**: Metrics and health checks for API Key operations

### H2 Database Storage Advantages

Using H2 database storage for API Keys provides the following advantages:

1. **Default Storage Method**: H2 database is now the default storage method for API Keys
2. **Persistence**: Data is not lost when the application restarts
3. **High Performance**: Embedded database with no network overhead
4. **Easy Maintenance**: Single database file, easy to backup
5. **Powerful Queries**: Support for complex SQL queries
6. **Transaction Support**: Ensures data consistency
7. **Visual Management**: H2 console for easy debugging
8. **Production Ready**: Meets production environment requirements

### H2 Database Table Structure

API Keys are stored in the H2 database in the following tables:

#### api_keys Table

| Field | Type | Description |
|-------|------|-------------|
| id | BIGINT | Primary key |
| key_id | VARCHAR(255) | Key ID |
| key_value_hash | VARCHAR(500) | Key value hash (no plaintext storage) |
| description | VARCHAR(1000) | Description |
| permissions | TEXT | Permission list (JSON) |
| expires_at | TIMESTAMP | Expiration time |
| enabled | BOOLEAN | Whether enabled |
| daily_token_limit | BIGINT | Daily Token usage limit |
| rate_limit_per_minute | INT | Requests per minute limit |
| quota_alert_threshold | INT | Alert threshold percentage |
| today_token_usage | BIGINT | Current day Token usage |
| today_request_count | INT | Current day request count |
| last_reset_time | TIMESTAMP | Last quota reset time |
| created_at | TIMESTAMP | Creation time |
| updated_at | TIMESTAMP | Update time |
| metadata | TEXT | Metadata (JSON) |
| usage_statistics | TEXT | Usage statistics (JSON) |

#### Index Optimization

The system automatically creates the following indexes to improve query performance:

- `idx_apikey_key_id`: Key ID index
- `idx_apikey_enabled`: Enabled status index
- `idx_apikey_expires_at`: Expiration time index
- `idx_apikey_created_at`: Creation time index

### API Key Storage Structure

API Keys are stored with the following metadata:

```json
{
  "id": "key-uuid-123",
  "keyId": "admin-key-001",
  "keyValueHash": "sha256-hash-of-key",
  "description": "Administrator API Key",
  "permissions": ["admin", "read", "write", "delete"],
  "expiresAt": "2027-12-31T23:59:59",
  "enabled": true,
  "dailyTokenLimit": 100000,
  "rateLimitPerMinute": 100,
  "quotaAlertThreshold": 80,
  "todayTokenUsage": 15000,
  "todayRequestCount": 200,
  "lastResetTime": "2025-01-15T00:00:00Z",
  "createdAt": "2025-01-15T10:30:00Z",
  "updatedAt": "2025-01-15T10:30:00Z",
  "metadata": {
    "createdBy": "admin",
    "department": "IT"
  },
  "usageStatistics": {
    "totalRequests": 1000,
    "successfulRequests": 950,
    "failedRequests": 50,
    "lastUsedAt": "2025-01-15T10:30:00Z"
  }
}
```

## Monitoring and Auditing

### Usage Statistics

The system automatically records usage statistics for each API Key:

- Total number of requests
- Number of successful requests
- Number of failed requests
- Last usage time
- Daily usage volume

### Audit Logs

When audit functionality is enabled, the system records all authentication-related events:

```yaml
jairouter:
  security:
    audit:
      enabled: true
      event-types:
        api-key-created: true
        api-key-used: true
        api-key-revoked: true
        api-key-expired: true
        authentication-success: true
        authentication-failure: true
```

### Monitoring Metrics

Security metrics are registered by `monitor/monitoring/security/SecurityMetrics.java` (Micrometer; Prometheus counters get the `_total` suffix):

| Metric (Micrometer / Prometheus) | Type | Description |
|----------------------------------|------|-------------|
| `jairouter.security.authentication.attempts(_total)` | Counter | Total auth attempts |
| `jairouter.security.authentication.successes(_total)` | Counter | Total auth successes (tag: `auth_type`) |
| `jairouter.security.authentication.failures(_total)` | Counter | Total auth failures (tags: `auth_type`, `failure_reason`) |
| `jairouter.security.authentication.duration` | Timer | Auth processing time |
| `jairouter.security.jwt.validations(_total)` | Counter | Total JWT validations (tag: `result`) |
| `jairouter.security.jwt.refreshes(_total)` | Counter | Total JWT refreshes |
| `jairouter.security.jwt.expired(_total)` | Counter | Total expired JWTs |
| `jairouter.security.sanitization.operations(_total)` | Counter | Total sanitization operations |
| `jairouter.security.sanitization.rule.matches(_total)` | Counter | Total sanitization rule matches |
| `jairouter.security.violations(_total)` | Counter | Total security violations |
| `jairouter.security.suspicious.activities(_total)` | Counter | Total suspicious activities |
| `jairouter.security.active.users` | Gauge | Currently active authenticated users |
| `jairouter.security.apikeys.active` | Gauge | API keys currently in use |

> Note: earlier docs listed `jairouter_security_api_keys_created_total`, `_used_total`, `_revoked_total`, which are **not** registered in the current code. Query quota usage via the management API (`GET /api/auth/api-keys/{keyId}/quota`, `/quota/overview`).

## Security Auditing and Monitoring

### Enhanced Audit Configuration

```yaml
jairouter:
  security:
    # Enhanced audit configuration
    audit:
      enabled: true
      log-level: "INFO"
      include-request-body: false
      include-response-body: false
      retention-days: 90
      
      # API Key operations auditing
      api-key-operations:
        enabled: true
        log-key-details: false  # Don't log full keys
        log-usage-patterns: true
        log-ip-address: true
      
      # JWT operations auditing
      jwt-operations:
        enabled: true
        log-token-details: false  # Don't log full tokens
        log-user-agent: true
        log-ip-address: true
      
      # Security events auditing
      security-events:
        enabled: true
        suspicious-activity-detection: true
        alert-thresholds:
          failed-auth-per-minute: 10
          api-key-usage-per-minute: 100
          token-revoke-per-minute: 5
      
      # Audit storage configuration
      storage:
        type: "h2"              # Options: h2, file, database
        h2:
          table-name: "security_audit_events"  # H2 table name
        file-path: "logs/security-audit.log"
        rotation:
          max-file-size: "100MB"
          max-files: 30
        # Optional: database storage
        database:
          enabled: false
          table-name: "security_audit_events"
```

### Audit Event Types

The system logs the following API Key and JWT events:

#### API Key Events
- **Key Created**: When a new API key is generated
- **Key Used**: When an API key is used for authentication
- **Key Revoked**: When an API key is revoked
- **Key Expired**: When an API key expires

#### JWT Token Events
- **Token Issued**: When a new JWT token is created
- **Token Refreshed**: When an access token is refreshed
- **Token Revoked**: When a token is manually revoked
- **Token Validated**: When a token is validated (success/failure)
- **Token Expired**: When a token naturally expires

#### Security Events
- **Suspicious Activity**: Unusual authentication patterns
- **Failed Authentication**: Failed login attempts
- **Bulk Operations**: Mass token/key operations

### Audit Event Structure

```json
{
  "id": "audit-event-uuid",
  "type": "API_KEY_USED",
  "userId": "user123",
  "resourceId": "key-uuid-123",
  "action": "USE_KEY",
  "details": "Using API Key to access service",
  "ipAddress": "192.168.1.100",
  "userAgent": "Mozilla/5.0...",
  "success": true,
  "timestamp": "2025-01-15T10:30:00Z",
  "metadata": {
    "keyId": "admin-key-001",
    "endpoint": "/v1/chat/completions",
    "method": "POST"
  }
}
```

### Security Report Generation

Generate comprehensive security reports:

```bash
# Get security report for the last 30 days
curl -X GET "http://localhost:8080/api/security/audit/report?from=2025-01-01&to=2025-01-31" \
     -H "Authorization: Bearer admin_token"
```

Response includes:
- Total API Key and JWT operations
- Failed authentication statistics
- Suspicious activity alerts
- Top IP addresses and users
- Security event trends

## Best Practices

### 1. API Key Security

- **Use Strong Keys**: API Keys should be long and random, at least 32 characters recommended
- **Regular Rotation**: Regularly rotate API Keys, especially when personnel changes occur
- **Environment Variables**: Do not hardcode API Keys in configuration files, use environment variables
- **Principle of Least Privilege**: Only grant necessary permissions

### 2. Expiration Time Management

- **Reasonable Expiration Settings**: Set appropriate expiration times based on usage scenarios
- **Early Renewal**: Renew API Keys in a timely manner before they expire
- **Expiration Monitoring**: Set up alerts to monitor API Keys that are about to expire

### 3. Permission Management

- **Hierarchical Authorization**: Assign different permission levels based on user roles
- **Regular Review**: Regularly review API Key permission assignments
- **Timely Revocation**: Disable or delete unused API Keys promptly

### 4. Performance Optimization

- **Enable Caching**: Enable Redis caching in production environments
- **Reasonable Cache Time Settings**: Set cache expiration times based on security requirements and performance needs
- **Monitor Cache Hit Rate**: Monitor cache performance and adjust configurations in a timely manner

## Troubleshooting

### Common Issues

#### 1. Authentication Failure

**Issue**: Client receives 401 Unauthorized error

**Possible Causes**:
- Incorrect API Key
- Expired API Key
- Disabled API Key
- Incorrect request header name

**Solutions**:
1. Check if the API Key is correct
2. Check if the API Key has expired
3. Check the `header-name` setting in configuration
4. View audit logs for detailed error information

#### 2. Insufficient Permissions

**Issue**: Client receives 403 Forbidden error

**Possible Causes**:
- Insufficient API Key permissions
- Accessed an interface requiring higher permissions

**Solutions**:
1. Check API Key permission configuration
2. Add necessary permissions to the API Key
3. Use an API Key with sufficient permissions

#### 3. Performance Issues

**Issue**: Authentication response time is too long

**Possible Causes**:
- Cache not enabled or improperly configured
- Redis connection issues
- Too many API Keys

**Solutions**:
1. Enable and properly configure caching
2. Check Redis connection status
3. Optimize API Key configuration
4. Monitor authentication performance metrics

### Debugging Tips

#### 1. Enable Detailed Logging

```yaml
logging:
  level:
    org.unreal.modelrouter.auth: DEBUG
```

#### 2. Check Audit Logs

```bash
tail -f logs/security-audit.log | grep authentication
```

#### 3. Monitor Metrics

Access the Prometheus metrics endpoint:
```
http://localhost:8080/actuator/prometheus
```

#### 4. Health Check

Check system health status:
```
http://localhost:8080/actuator/health
```

## Advanced Features

### Bulk Import/Export (v1.6.2+)

Trunkline supports bulk import/export of API keys for migration, backup and batch management.

#### Export API Keys

```bash
# Export all API keys
curl -X GET "http://localhost:8080/api/auth/api-keys/export" \
     -H "Authorization: Bearer admin_token"
```

Response format:
```json
{
  "keys": [
    {
      "keyId": "admin-key-001",
      "description": "Administrator key",
      "permissions": ["admin"],
      "expiresAt": "2027-12-31T23:59:59",
      "enabled": true,
      "allowedIpAddresses": ["192.168.1.100"],
      "dailyRequestLimit": 10000,
      "rotationPeriodDays": 90
    }
  ],
  "exportedAt": "2026-04-10T10:30:00Z",
  "totalCount": 10
}
```

#### Import API Keys

```bash
# Import keys (MERGE mode - keep existing keys)
curl -X POST "http://localhost:8080/api/auth/api-keys/import" \
     -H "Authorization: Bearer admin_token" \
     -H "Content-Type: application/json" \
     -d '{
       "mode": "MERGE",
       "keys": [
         {
           "keyId": "new-key-001",
           "description": "Newly imported key",
           "permissions": ["chat"],
           "expiresAt": "2026-12-31T23:59:59"
         }
       ]
     }'

# Import keys (REPLACE mode - replace all existing keys)
curl -X POST "http://localhost:8080/api/auth/api-keys/import" \
     -H "Authorization: Bearer admin_token" \
     -H "Content-Type: application/json" \
     -d '{
       "mode": "REPLACE",
       "keys": [...]
     }'
```

Import result:
```json
{
  "success": true,
  "importedCount": 5,
  "skippedCount": 2,
  "errorCount": 0,
  "details": [
    {"keyId": "new-key-001", "status": "IMPORTED", "message": "Imported"},
    {"keyId": "existing-key", "status": "SKIPPED", "message": "Existing keys are skipped in MERGE mode"}
  ]
}
```

### Automatic Key Rotation (v1.6.2+)

Configure a rotation period to issue new key values periodically and improve security.

#### Configure the Rotation Period

```yaml
jairouter:
  security:
    api-key:
      keys:
        - key-id: "auto-rotate-key"
          key-value: "${INITIAL_KEY_VALUE}"
          rotation-period-days: 90  # auto-rotate every 90 days
```

#### How Rotation Works

- **Automatic**: `ApiKeyRotationScheduler` checks keys due for rotation hourly
- **Trigger**: keys whose last rotation is older than `rotationPeriodDays` are rotated automatically
- **Key generation**: a new strong random key value is generated
- **History**: rotation history is kept for audit

#### Manual Rotation

```bash
# Manually reissue a key value
curl -X POST "http://localhost:8080/api/auth/api-keys/{keyId}/reset" \
     -H "Authorization: Bearer admin_token"
```

### IP Whitelist (v1.6.1+)

Restrict a key to specific source IP addresses.

#### Configure the IP Whitelist

```yaml
jairouter:
  security:
    api-key:
      keys:
        - key-id: "restricted-key"
          key-value: "${KEY_VALUE}"
          allowed-ip-addresses:
            - "192.168.1.100"      # single IP
            - "10.0.0.0/8"         # CIDR range
            - "172.16.0.0-172.16.255.255"  # IP range
```

#### Endpoint Configuration

```bash
# Update the IP whitelist
curl -X PUT "http://localhost:8080/api/auth/api-keys/{keyId}" \
     -H "Authorization: Bearer admin_token" \
     -H "Content-Type: application/json" \
     -d '{
       "allowedIpAddresses": ["192.168.1.100", "10.0.0.0/8"]
     }'
```

### Daily Request Limit (v1.6.1+)

Cap the number of requests per day for each key to prevent abuse.

#### Configure the Daily Limit

```yaml
jairouter:
  security:
    api-key:
      keys:
        - key-id: "limited-key"
          key-value: "${KEY_VALUE}"
          daily-request-limit: 10000  # at most 10000 requests per day
```

#### How the Limit Works

- **Counting**: counted per UTC day, reset at 00:00
- **On exceed**: returns 429 Too Many Requests
- **Query**: current usage can be queried via the management API

### Expired-Key Auto Cleanup (v1.6.2+)

`ApiKeyExpirationScheduler` automatically disables expired API keys.

#### Cleanup Behavior

- **Frequency**: checked hourly
- **Action**: expired keys are set to `enabled: false`
- **Notification**: alerting can be configured for keys about to expire

### Admin API Rate Limiting (v1.6.1+)

To prevent abuse of the management API (`/api/auth/api-keys/**`), `AdminApiRateLimiter` limits by client IP (overridable via `jairouter.auth.admin-api-rate-limit.*`):

| Operation | Per minute | Per hour |
|-----------|-----------|----------|
| Read (GET/HEAD) | 120 | 2000 |
| Write (POST/PUT/PATCH/DELETE) | 30 | 200 |
| Create key (POST) | — | 10 |

On exceed it returns `429 Too Many Requests` (body `{"code":"RATE_LIMIT_EXCEEDED"}`).

## Example Configurations

### Development Environment

```yaml
jairouter:
  security:
    enabled: true
    api-key:
      enabled: true
      default-expiration-days: 30
      keys:
        - key-id: "dev-admin"
          key-value: "dev-admin-key-12345"
          permissions: ["admin"]
          expires-at: "2027-12-31T23:59:59"
```

### Production Environment

```yaml
jairouter:
  security:
    enabled: true
    api-key:
      enabled: true
      cache-enabled: true
      keys:
        - key-id: "prod-admin"
          key-value: "${PROD_ADMIN_API_KEY}"
          permissions: ["admin"]
          expires-at: "${PROD_ADMIN_KEY_EXPIRES}"
        - key-id: "prod-service"
          key-value: "${PROD_SERVICE_API_KEY}"
          permissions: ["chat"]
          allowed-groups: ["prod-chat"]
          expires-at: "${PROD_SERVICE_KEY_EXPIRES}"
    performance:
      cache:
        redis:
          enabled: true
          host: "${REDIS_HOST}"
          port: "${REDIS_PORT}"
          password: "${REDIS_PASSWORD}"
```

## Related Documentation

- [JWT Authentication Configuration](jwt-authentication.md)
- [Data Sanitization Rules Configuration](data-sanitization.md)
- [Security Feature Troubleshooting Guide](troubleshooting.md)