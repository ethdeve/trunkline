# API Reference

<!-- 版本信息 -->
> **Doc Version**: 1.0.4  
> **Last Updated**: 2026-10-28  
> **Git Commit**: 61384b4a  
> **Author**: Lincoln
<!-- /版本信息 -->



Trunkline provides OpenAI-compatible APIs for various AI services, along with management APIs for configuration and monitoring.

## API Categories

### 1. Universal APIs (`/v1/*`)

OpenAI / Anthropic-compatible endpoints for AI services:

- **[Universal API](universal-api.md)** - Chat, embeddings, rerank, model list; plus Anthropic-native `/v1/messages` and `/v1/messages/count_tokens`
- Compatible with OpenAI SDKs and tools (point the Anthropic SDK at `/v1/messages`)
- Consistent request/response format
- The `model` field must be a **model group name** (not an instance name) — see the "API Key & Group Routing Semantics" chapter in the universal API doc

### 2. Management APIs (`/api/*`)

Trunkline-specific management endpoints:

- **[Management API](management-api.md)** - Dynamic configuration management
- **[Monitoring API](monitoring-api.md)** - Health checks and metrics

### 3. Actuator APIs (`/actuator/*`)

Spring Boot actuator endpoints for monitoring:

- Health checks
- Metrics
- Application info

## Base URL

All APIs are served from your Trunkline instance:

```
http://localhost:8080
```

## Authentication

By default, Trunkline doesn't require authentication. For production deployments, consider:

- Reverse proxy with authentication (nginx, Apache)
- API gateway with authentication
- Custom authentication filters

## Rate Limiting

All APIs are subject to configured rate limits. When limits are exceeded, you'll receive:

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/json

{
  "error": {
    "message": "Rate limit exceeded",
    "type": "rate_limit_error",
    "code": "rate_limit_exceeded"
  }
}
```

## Error Handling

Trunkline provides consistent error responses across all APIs:

### Standard Error Format

```json
{
  "error": {
    "message": "Error description",
    "type": "error_type",
    "code": "error_code",
    "details": {
      "additional": "information"
    }
  }
}
```

### Common Error Codes

| HTTP Status | Error Type | Description |
|-------------|------------|-------------|
| 400 | `invalid_request_error` | Malformed request |
| 404 | `not_found_error` | Resource not found |
| 429 | `rate_limit_error` | Rate limit exceeded |
| 500 | `api_error` | Internal server error |
| 503 | `api_error` | No available instances |
| 503 | `api_error` | Circuit breaker is open |

## Content Types

### Request Content Types

- `application/json` - JSON requests (most APIs, incl. image generation)
- `multipart/form-data` - `/api/v1/audio/transcriptions` (STT) and `/api/v1/images/edits` (image editing) declare this consumed type

### Response Content Types

- `application/json` - JSON responses (most APIs, incl. image generation and image editing)

## Request/Response Examples

### Chat Completion

**Request:**
```http
POST /v1/chat/completions
Content-Type: application/json

{
  "model": "chat-groups",
  "messages": [
    {
      "role": "user",
      "content": "Hello!"
    }
  ],
  "max_tokens": 100,
  "temperature": 0.7
}
```

**Response:**
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "id": "chatcmpl-123",
  "object": "chat.completion",
  "created": 1677652288,
  "model": "chat-groups",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Hello! How can I help you today?"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 9,
    "completion_tokens": 12,
    "total_tokens": 21
  }
}
```

### Embeddings

**Request:**
```http
POST /v1/embeddings
Content-Type: application/json

{
  "model": "text-embedding-ada-002",
  "input": "Hello world"
}
```

**Response:**
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "object": "list",
  "data": [
    {
      "object": "embedding",
      "embedding": [0.1, 0.2, 0.3, ...],
      "index": 0
    }
  ],
  "model": "text-embedding-ada-002",
  "usage": {
    "prompt_tokens": 2,
    "total_tokens": 2
  }
}
```

## Streaming Responses

Trunkline supports streaming for chat completions:

**Request:**
```http
POST /v1/chat/completions
Content-Type: application/json

{
  "model": "chat-groups",
  "messages": [{"role": "user", "content": "Hello!"}],
  "stream": true
}
```

**Response:**
```http
HTTP/1.1 200 OK
Content-Type: text/event-stream

data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1677652288,"model":"qwen2.5:7b","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1677652288,"model":"qwen2.5:7b","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}

data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1677652288,"model":"qwen2.5:7b","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":"stop"}]}

data: [DONE]
```

## SDK Compatibility

Trunkline is compatible with OpenAI SDKs. Simply change the base URL:

### Python (openai)
```python
from openai import OpenAI

client = OpenAI(
    base_url="http://localhost:8080/v1",
    api_key="not-needed"
)
```

### Node.js (openai)
```javascript
import OpenAI from 'openai';

const openai = new OpenAI({
  baseURL: 'http://localhost:8080/v1',
  apiKey: 'not-needed'
});
```

### curl
```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model": "chat-groups", "messages": [{"role": "user", "content": "Hello!"}]}'
```

## API Versioning & Entry Surfaces

Trunkline "versioning" is expressed through **path prefixes**, not a separate version segment:

- `/v1/**` — the OpenAI / Anthropic native inference surface (`OpenAiNativeController` / `AnthropicMessagesController`) for SDK direct connections;
- `/api/v1/**` — the console inference surface (`UniversalController`), wrapped in `RouterResponse`;
- `/api/**` — the management and monitoring surface (console JWT/API-key auth).

The product semantic version is `project.version` in `pom.xml` (currently **3.3.1**); the `/v1` path prefix stays stable, and future evolution is additive and backward-compatible.

## Pagination

Some list endpoints support `?page=&size=` pagination (code: `JwtTokenController#tokens`, `SecurityBlacklistController#list`, `ExtendedSecurityAuditController`, `CircuitBreakerConfigController` history, `TracingQueryController#search`):

```http
GET /api/auth/jwt/tokens?page=0&size=20
```

**Response (Spring Data Page shape):**
```json
{
  "content": [...],
  "page": {
    "number": 0,
    "size": 20,
    "totalElements": 25,
    "totalPages": 2
  }
}
```

> Note: page bases differ across endpoints — most default to `page=0` (0-based), while the tracing-query endpoint defaults to `page=1` (1-based). Not every list endpoint is paginated.

## CORS Support

Trunkline allows browser cross-origin calls: global CORS is explicitly disabled in `SecurityConfiguration:97` (`.cors(cors -> cors.disable())`), and CORS instead takes effect through **controller-class-level** `@CrossOrigin(origins = "*")` annotations (15+ controllers declare it in code).

Actual response headers look like:

```http
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS
Access-Control-Allow-Headers: Content-Type, Authorization
```

> Note: controllers without `@CrossOrigin` do not return CORS headers; browser front-ends can only call the annotated endpoints directly.

## Health Checks

Check API health:

```http
GET /actuator/health
```

**Response:**
```json
{
  "status": "UP",
  "components": {
    "diskSpace": {"status": "UP"},
    "ping": {"status": "UP"}
  }
}
```

## Next Steps

- **[Universal API](universal-api.md)** - OpenAI-compatible endpoints
- **[Management API](management-api.md)** - Configuration management
- **[Monitoring API](monitoring-api.md)** - Health and metrics
- **[OpenAPI Specification](openapi-spec.md)** - Interactive API documentation