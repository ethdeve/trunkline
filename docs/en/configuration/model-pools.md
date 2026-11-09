# Model Pools

<!-- 版本信息 -->
> **Doc Version**: 1.0.0  
> **Last Updated**: 2026-10-28
> **Author**: Lincoln
<!-- /版本信息 -->

## Overview

A model pool packages a group of instances of the **same service type** into a named collection. When the client sets the request `model` field to the **pool name**, the gateway automatically distributes requests across healthy members inside the pool — the client never references concrete backends.

- Config prefix: `model.pools` (default empty list `[]`; without pools the routing behavior is unchanged). The former default file `config/router/pools.yml` was removed on 2026-11-09; to define pools in YAML, use `application-{profile}.yml` or an external mount.
- Persistence: pools created/edited in the admin UI are persisted to StoreManager (key=`pool_definitions`) and merged over YAML (same `pool-name`: persisted wins)
- Special pool name `auto-model`: when no such pool is configured, `model=auto-model` falls back to **all healthy instances** of that service type; once a pool of that name exists, the pool definition applies

## Configuration Structure

```yaml
# application-{profile}.yml or external mount
model:
  pools:
    - pool-name: auto-model        # pool name (the virtual model name clients put in `model`)
      name: Default auto-routing pool  # display name
      service-type: chat           # service type (chat/embedding/rerank/tts/stt/imgGen/imgEdit)
      enabled: true                # enabled flag
      strategy: weighted-random    # member selection strategy (see table)
      members:                     # pool members (referenced by instance id)
        - instance-id: inst-gpt
          weight: 9                # pool-level weight (default 1)
          model-name: gpt-4o       # optional: model name echoed in responses (defaults to instance name)
        - instance-id: inst-claude
          weight: 1
```

### Field Reference

| Field | Required | Description |
|-------|----------|-------------|
| `pool-name` | yes | Unique pool id; a request with `model=<pool-name>` hits this pool |
| `name` | no | Display name |
| `service-type` | yes | Service type; all members must belong to it |
| `enabled` | no | Whether enabled, default `true` |
| `strategy` | no | Member selection strategy, default `weighted-random` |
| `members[].instance-id` | yes | References `instanceId` from instance management; deleted instances are skipped automatically (warn log) |
| `members[].weight` | no | Pool-level weight (default 1); **overrides** the instance's own `weight` |
| `members[].model-name` | no | Model name echoed in responses (defaults to instance name) |

### Member Selection Strategies

| Strategy | Description |
|----------|-------------|
| `weighted-random` | Weighted random (default) by pool-level weight |
| `round-robin` | Round robin |
| `least-connections` | Fewest active connections |
| `ip-hash` | Client-IP hash session stickiness |
| `consistent-hash` | Consistent hash (accepted by the controller; ignores weight) |

> Selection follows the same chain as global load balancing: **health/circuit-breaker filtering** first (unhealthy/open instances excluded), then the pool strategy. Pool-level weight overrides instance weight.

## Request Semantics

### Auto routing: `model` field = pool name

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "auto-model",
    "messages": [{"role": "user", "content": "Hello"}]
  }'
```

- `model` = pool name (e.g. `auto-model`) → resolved to the pool; traffic is distributed among healthy members per `strategy`
- `model` = a plain instance name → original instance matching, unaffected by pools
- With no pools configured, `auto-model` falls back to all healthy instances of the service

### Routing Rules Integration

A routing rule's `TARGET_MODEL` action may directly target a pool name; rule hits then follow pool routing (see [Routing Rules](routing-rules.md)).

### Response Echo

After pool routing, the response and the outbound request's `model` field carry the actual selected instance's model name (non-streaming rewritten directly; streaming outbound requests rewritten synchronously).

## Management API

Base path: `/api/config/pools`

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/config/pools/list` | GET | List all pools |
| `/api/config/pools/{poolName}` | GET | Get one pool definition |
| `/api/config/pools` | POST | Create a pool (`poolName` required; body includes members and quota fields) |
| `/api/config/pools/{poolName}` | PUT | Update a pool |
| `/api/config/pools/{poolName}` | DELETE | Delete a pool |
| `/api/config/pools/{poolName}/quota/usage` | GET | Inspect pool quota usage |
| `/api/config/pools/{poolName}/quota/reset` | POST | Reset pool quota counters |

### Create Example

```bash
curl -X POST http://localhost:8080/api/config/pools \
  -H "Content-Type: application/json" \
  -d '{
    "poolName": "auto-model",
    "name": "Default auto-routing pool",
    "serviceType": "chat",
    "enabled": true,
    "strategy": "weighted-random",
    "members": [
      {"instanceId": "inst-gpt", "weight": 9},
      {"instanceId": "inst-claude", "weight": 1}
    ]
  }'
```

## Pool-Level Quotas

A pool can carry a **shared budget** (all API keys share one budget), across minute/hour/day/month windows × request count / token count:

| Field | Description |
|-------|-------------|
| `rateLimitPerMinute` | Requests per minute (0 = unlimited) |
| `hourRequests` / `hourTokens` | Per-hour requests / tokens |
| `dayRequests` / `dayTokens` | Per-day requests / tokens |
| `monthRequests` / `monthTokens` | Per-month requests / tokens |
| `quotaAlertThreshold` | Alert threshold ratio (default 0.8; alert at 80% usage) |

- Quota dimension id: `group_id = pool name`
- Usage query: `GET /api/config/pools/{poolName}/quota/usage`
- Manual reset: `POST /api/config/pools/{poolName}/quota/reset`

> Pool quotas live in the runtime quota ledger. When the multi-dimensional ledger is enabled (`trunkline.quota.enabled`), the pool dimension is one aggregation view. See [Quota Ledger](quota.md).

## Known Boundaries

- Members are referenced by `instance-id`; deleting an instance skips its members automatically (warn log) — no auto replacement
- Pool strategy is independent of the global `model.load-balance.type`; in-pool selection follows the pool `strategy`
- Selected instances still go through the existing service-level / instance-level rate limiting and circuit breaker chain

## Related Documentation

- [Routing Rules](routing-rules.md) - TARGET_MODEL may target a pool name directly
- [Rate Limiting](rate-limiting.md) - rate limit chain applied to pool members
- [Quota Ledger](quota.md) - multi-dimensional quota ledger
