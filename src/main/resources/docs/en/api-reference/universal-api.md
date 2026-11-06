# Unified API Interface

<!-- 版本信息 -->
> **Doc Version**: 2.2.0  
> **Last Updated**: 2026-10-28  
> **Main change**: Controller-verified — added `GET /v1/models`, `POST /v1/messages`, `POST /v1/messages/count_tokens`; corrected `/api/v1/images/edits` to multipart/form-data; new "API key & group routing semantics" section  
> **Git Commit**: 61384b4a  
> **Author**: Lincoln
<!-- /版本信息 -->


## Important Change Notes (v2.0.0)

### DTO Refactor

To improve API maintainability and extensibility, the request DTOs were refactored:

**Before**: all parameters sat at the same level, resulting in a bloated parameter dictionary (ChatDTO had 56 fields).

**After**:
- **Core fields**: only required and commonly-used parameters kept (ChatDTO: 11 fields)
- **Extension options**: adapter-specific parameters moved into an `options` object (ChatDTO: 29 fields)

**Benefits**:
- ✅ Clear core parameters, easy to get started
- ✅ Extension parameters are opt-in and don't affect simple scenarios
- ✅ Backward compatible — existing requests still work
- ✅ Easy to add new parameters later

**Example**:
```json
{
  "model": "qwen3:4b",
  "messages": [{"role": "user", "content": "Hello"}],
  "temperature": 0.7,
  "options": {
    "repeat_penalty": 1.1,
    "seed": 42
  }
}
```



Trunkline provides a unified API interface compatible with OpenAI format, supporting multiple AI model services. The OpenAI-compatible surface lives under `/v1` (plus the Anthropic-native `/v1/messages`); TTS / STT / image endpoints are exposed **only on the console surface** `/api/v1/**` (see the table below).

## Two entry surfaces (since v3.1)

| Surface | Path | Response shape | Intended use |
|---|---|---|---|
| **OpenAI-native** | `/v1/**` | Non-streaming: **raw JSON** (`{id, object:"chat.completion", choices, usage}`); streaming: **native SSE** (`data: {...}` separated by `\n\n`) | Point OpenAI SDK / LangChain / LlamaIndex at `base_url=http://<host>:8080/v1` |
| **Anthropic-native** | `/v1/messages`, `/v1/messages/count_tokens` | Anthropic Messages native shape (`{"type":"error","error":{"type","message"}}` on errors) | Point the Anthropic SDK at `/v1/messages` |
| **Console** | `/api/v1/**` | Wrapped in `RouterResponse` (`{success, message, data}`) | Web console / AI playground internal calls; **not advertised as OpenAI-compatible** |

**Authentication**

- Gateway credential: `X-API-Key: <API key created in the console>`, or `Jairouter_Token: <JWT>`.
- The `Authorization` header is **forwarded to the downstream AI service**; **instance-level `headers` take precedence** — so the downstream key can live only on the instance (`headers: {Authorization: "Bearer <downstream-key>"}`) with no client-side duplication.
- Service permission: the API key must carry the matching service role (`CHAT` / `EMBEDDING` / `RERANK`, or `ADMIN`).
- Missing credentials → `401`; insufficient permission → `403`; quota exceeded → `429` (with `Retry-After` and `X-Quota-*`).

> **Changed in v3.1**: `/v1/**` used to be forwarded to `/api/v1/**` by a path-rewrite filter that lost the request body (always `400 Failed to read HTTP message`). It is now served natively by `OpenAiNativeController`, so `/v1` genuinely works as an OpenAI SDK `base_url`.

## Model List Interface

### `GET /v1/models`

Returns the model groups (virtual model names) callable by the current key/session, in OpenAI shape:

```json
{
  "object": "list",
  "data": [
    {
      "id": "chat-groups",
      "object": "model",
      "created": 1677652288,
      "owned_by": "trunkline"
    }
  ]
}
```

> Code: `OpenAiNativeController#listModels` → `ModelCatalogService.listCallableModelsAsOpenAiList()`. Under the v3.5 single-track governance, instance names (physical model names) are no longer directly callable (the routing layer disables direct instance calls and the key whitelist only recognizes groups), so this endpoint lists groups only.
>
> The console also has `GET /api/models` (`ModelInfoController`), which returns a `RouterResponse`-wrapped full inventory (groups + instances) for internal console use — **not** OpenAI-shaped.

## Chat Completion Interface

### `POST /v1/chat/completions`

Handles chat completion requests, supporting both streaming and non-streaming responses.

#### Request Parameters

```json
{
  "model": "gpt-3.5-turbo",
  "messages": [
    {
      "role": "system",
      "content": "You are a helpful assistant."
    },
    {
      "role": "user", 
      "content": "Hello!"
    }
  ],
  "stream": false,
  "max_tokens": 1000,
  "temperature": 0.7,
  "top_p": 1.0,
  "top_k": 50,
  "frequency_penalty": 0.0,
  "presence_penalty": 0.0,
  "stop": null,
  "user": "user-123"
}
```

#### Parameter Description

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `model` | string | Yes | The name of the model to use |
| `messages` | array | Yes | List of conversation messages |
| `stream` | boolean | No | Whether to enable streaming response, default is false |
| `max_tokens` | integer | No | Maximum number of tokens to generate |
| `temperature` | number | No | Sampling temperature between 0-2, default is 1 |
| `top_p` | number | No | Nucleus sampling parameter between 0-1, default is 1 |
| `top_k` | integer | No | Top-K sampling parameter |
| `frequency_penalty` | number | No | Frequency penalty between -2 to 2, default is 0 |
| `presence_penalty` | number | No | Presence penalty between -2 to 2, default is 0 |
| `stop` | string/array | No | Stop sequences |
| `user` | string | No | User identifier |

#### Message Format

```json
{
  "role": "user|assistant|system",
  "content": "Message content",
  "name": "Optional sender name"
}
```

#### Response Format

**Non-streaming Response:**

```json
{
  "id": "chatcmpl-123",
  "object": "chat.completion",
  "created": 1677652288,
  "model": "gpt-3.5-turbo",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Hello! I am an AI assistant, happy to serve you."
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 20,
    "completion_tokens": 15,
    "total_tokens": 35
  },
  "system_fingerprint": "fp_44709d6fcb"
}
```

**Streaming Response:**

```json
data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1677652288,"model":"gpt-3.5-turbo","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1677652288,"model":"gpt-3.5-turbo","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":null}]}

data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1677652288,"model":"gpt-3.5-turbo","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

## Text Embedding Interface

### `POST /v1/embeddings`

Converts text into vector representations for semantic search, similarity calculations, and other tasks.

#### Request Parameters

```json
{
  "model": "text-embedding-ada-002",
  "input": "Text content to embed",
  "encoding_format": "float",
  "dimensions": 1536,
  "user": "user-123"
}
```

#### Parameter Description

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `model` | string | Yes | Embedding model name |
| `input` | string/array | Yes | Text to embed, can be a string or array of strings |
| `encoding_format` | string | No | Encoding format, supports "float" or "base64" |
| `dimensions` | integer | No | Output vector dimensions |
| `user` | string | No | User identifier |

#### Response Format

```json
{
  "object": "list",
  "data": [
    {
      "object": "embedding",
      "embedding": [0.0023064255, -0.009327292, ...],
      "index": 0
    }
  ],
  "model": "text-embedding-ada-002",
  "usage": {
    "prompt_tokens": 8,
    "total_tokens": 8
  }
}
```

## Reranking Interface

### `POST /v1/rerank`

Reorders document lists based on relevance to a query.

#### Request Parameters

```json
{
  "model": "rerank-multilingual-v2.0",
  "query": "What is artificial intelligence?",
  "documents": [
    "Artificial intelligence is a branch of computer science",
    "Machine learning is a subfield of artificial intelligence",
    "Deep learning uses neural networks"
  ],
  "top_n": 3,
  "return_documents": true
}
```

#### Parameter Description

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `model` | string | Yes | Reranking model name |
| `query` | string | Yes | Query text |
| `documents` | array | Yes | List of documents to rank |
| `top_n` | integer | No | Number of documents to return |
| `return_documents` | boolean | No | Whether to return document content |

#### Response Format

```json
{
  "id": "rerank-123",
  "results": [
    {
      "index": 0,
      "score": 0.95,
      "document": "Artificial intelligence is a branch of computer science"
    },
    {
      "index": 1,
      "score": 0.87,
      "document": "Machine learning is a subfield of artificial intelligence"
    }
  ],
  "model": "rerank-multilingual-v2.0",
  "usage": {
    "total_tokens": 25
  }
}
```

## Anthropic Messages Interface

### `POST /v1/messages`

Chat completion in Anthropic Messages native shape (code: `AnthropicMessagesController`, routed to the chat service pool).

#### Request Parameters

```json
{
  "model": "chat-groups",
  "max_tokens": 1024,
  "system": "You are a helpful assistant.",
  "messages": [
    {"role": "user", "content": "Hello!"}
  ],
  "stream": false,
  "temperature": 0.7
}
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `model` | string | Yes | Model group name (same as the OpenAI surface: a group, not an instance name) |
| `messages` | array | Yes | Anthropic message list (`role` / `content`) |
| `max_tokens` | integer | Yes | Maximum tokens to generate |
| `system` | string | No | System prompt |
| `stream` | boolean | No | Stream (SSE) when true; default false |
| `temperature` | number | No | Sampling temperature |

#### Response Format

Non-streaming returns the Anthropic shape (`id` / `type:"message"` / `role` / `content` / `model` / `stop_reason` / `usage`); with `stream:true` it returns the Anthropic event stream as SSE.

> **Error-body difference**: errors on `/v1/messages*` use the Anthropic shape `{"type":"error","error":{"type","message"}}` — **no `code` field**; errors on all other `/v1/**` calls use the OpenAI shape `{"error":{"message","type","code"}}` (code: `V1ErrorBodyMapper#toBody`).

### `POST /v1/messages/count_tokens`

Counts tokens of `messages` (code: `AnthropicMessagesController#countTokens`). The request body is the same as `/v1/messages` (no `max_tokens` needed); the response follows the Anthropic counting shape (including `input_tokens`). This endpoint is a **local estimate and never calls the downstream model**.

> Authentication is the same as other `/v1/**` calls: `X-API-Key` or `Jairouter_Token`.

## Text-to-Speech Interface

### `POST /api/v1/audio/speech`

> **Console surface only.** The `/v1` surface registers `GET /v1/models`, `POST /v1/chat/completions`, `POST /v1/embeddings`, `POST /v1/rerank`, `POST /v1/messages`, `POST /v1/messages/count_tokens` — there is no `/v1/...` mapping for this endpoint.

Converts text into speech audio files.

#### Request Parameters

```json
{
  "model": "tts-1",
  "input": "Hello, this is a test voice.",
  "voice": "alloy",
  "response_format": "mp3",
  "speed": 1.0
}
```

#### Parameter Description

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `model` | string | Yes | TTS model name |
| `input` | string | Yes | Text to convert |
| `voice` | string | Yes | Voice type |
| `response_format` | string | No | Audio format, default is mp3 |
| `speed` | number | No | Speech speed between 0.25-4.0, default is 1.0 |

#### Response Format

Returns binary data of the audio file with Content-Type set according to `response_format`.

## Speech-to-Text Interface

### `POST /api/v1/audio/transcriptions`

> **Console surface only.** The `/v1` surface registers `GET /v1/models`, `POST /v1/chat/completions`, `POST /v1/embeddings`, `POST /v1/rerank`, `POST /v1/messages`, `POST /v1/messages/count_tokens` — there is no `/v1/...` mapping for this endpoint.

Converts audio files into text.

#### Request Parameters

Using `multipart/form-data` format:

```
file: (audio file)
model: whisper-1
language: zh
prompt: (optional prompt)
responseFormat: json
temperature: 0
```

#### Parameter Description

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | file | Yes | Audio file |
| `model` | string | Yes | STT model name |
| `language` | string | No | Audio language code |
| `responseFormat` | string | No | Response format, default is json |
| `temperature` | number | No | Sampling temperature |

#### Response Format

```json
{
  "text": "Transcribed text content"
}
```

## Image Generation Interface

### `POST /api/v1/images/generations`

> **Console surface only.** The `/v1` surface registers `GET /v1/models`, `POST /v1/chat/completions`, `POST /v1/embeddings`, `POST /v1/rerank`, `POST /v1/messages`, `POST /v1/messages/count_tokens` — there is no `/v1/...` mapping for this endpoint.

Generates images based on text descriptions.

#### Request Parameters

```json
{
  "model": "dall-e-3",
  "prompt": "A cute little cat playing in a garden",
  "n": 1,
  "size": "1024x1024",
  "quality": "standard",
  "response_format": "url",
  "style": "vivid",
  "user": "user-123"
}
```

#### Parameter Description

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `model` | string | Yes | Image generation model name |
| `prompt` | string | Yes | Image description text |
| `n` | integer | No | Number of images to generate, default is 1 |
| `size` | string | No | Image dimensions, default is 1024x1024 |
| `quality` | string | No | Image quality, standard or hd |
| `response_format` | string | No | Response format, url or b64_json |
| `style` | string | No | Image style, vivid or natural |
| `user` | string | No | User identifier |

#### Response Format

```json
{
  "created": 1677652288,
  "data": [
    {
      "url": "https://example.com/image.png",
      "revised_prompt": "Revised prompt"
    }
  ]
}
```

## Image Editing Interface

### `POST /api/v1/images/edits`

> **Console surface only.** The `/v1` surface registers `GET /v1/models`, `POST /v1/chat/completions`, `POST /v1/embeddings`, `POST /v1/rerank`, `POST /v1/messages`, `POST /v1/messages/count_tokens` — there is no `/v1/...` mapping for this endpoint.

Edits existing images.

#### Request Parameters

**`multipart/form-data`** request (code: `UniversalController#editImages` now declares `consumes = MediaType.MULTIPART_FORM_DATA_VALUE`, same as STT; the old `@RequestBody ImageEditDTO.Request` JSON style now yields `415 Unsupported Media Type`):

```
image: (image file, may repeat)
prompt: Edit description
model: dall-e-2
n: 1
size: 1024x1024
response_format: url
user: user-123
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `image` | file | Yes | Image file(s) to edit |
| `prompt` | string | Yes | Edit description |
| `model` | string | Yes | Image-editing model group name |
| `n` | integer | No | Number of images, default 1 |
| `size` | string | No | Image size, default 1024x1024 |
| `response_format` | string | No | Response format: url or b64_json |
| `user` | string | No | User identifier |

> By contrast, `/api/v1/audio/transcriptions` is also `multipart/form-data`, and its part name `responseFormat` is camelCase.

#### Response Format

```json
{
  "created": 1677652288,
  "data": [
    {
      "url": "https://example.com/edited-image.png"
    }
  ]
}
```

## API Key & Group Routing Semantics (how the `model` field is routed)

This chapter covers only the **request-side** resolution of the `model` field into a model group (pool) and how API-key constraints apply. Creating, rotating, and quota management of keys themselves are covered in the security section.

### Core rule: `model` must be a model group name

Under v3.5 single-track governance, the `model` field **only accepts model group names (pool names, i.e. the external virtual model names)**; instance names (physical model names) are no longer directly callable.

Resolution order (code: `ModelServiceRegistry#selectInstance` → `PoolSelector#findPool`):

1. Empty `model` → `400` (`IllegalArgumentException`).
2. **Rule engine first** (since v2.8.5): admin-configured routing rules may rewrite the target model (`TARGET_MODEL`), pin an instance (`TARGET_INSTANCE`), override the load-balancing strategy, or trigger rate limiting (`RATE_LIMIT`) — this is console configuration, **not client-controllable**.
3. **Tag filtering**: the request header `X-JAiRouter-Tags` (or rule `TARGET_TAGS`) filters candidate instances by tags.
4. **Exact group match**: within the service type implied by the endpoint (`chat` / `embedding` / `rerank` / `tts` / `stt` / `imgGen` / `imgEdit`), match enabled group names by exact string equality (`pool.getPoolName().equals(modelName)`). On match, group members become the candidate instance set and the group's configured load-balancing strategy is used.
5. **System group `auto-model`**: a built-in virtual group name meaning all healthy instances of the service type.
6. **No match → `404`**, message like: `Model 'X' is not a model group. Requests must target a model group (pool); direct model access is not allowed.`

When a group matches but has no candidate instances (no healthy members) → **`503`** (`PoolUnavailableException`), with `Retry-After: 60` and per-member unavailability reasons.

### No "pool/model" lock syntax exists

There is **no** code that splits the `model` field on `/` — `model` is matched as a whole against group names. There is no `pool/model` or `group/instance` syntax. To pin a request to a specific instance, an admin must configure `TARGET_INSTANCE` in the rule engine, or inspect the debug response headers.

### How the key whitelist constrains `model` resolution

An API key carries an `allowedGroups` field (model-group whitelist; code: `auth/security/config/properties/ApiKey.java`):

| `allowedGroups` | Behavior |
|---|---|
| `null` / empty | **No restriction** (legacy compatibility): the key may call any group within its service permissions |
| non-empty | The request `model` must satisfy `allowedGroups.contains(modelName)`, otherwise **`403`** |

Code: `ServiceRequestHandler#hasModelPermission` (`allowedGroups == null || isEmpty() → true`; otherwise `allowedGroups.contains(modelName)`).

Notes:

- This whitelist applies **only to API Key callers**; console JWT callers are exempt from the model whitelist check (`hasModelPermission` returns true when `caller.isJwtCaller()`).
- Service-level permission is a separate gate: the API key must hold the matching service role (`CHAT` / `EMBEDDING` / `RERANK` / `TTS` / `STT` / `IMG_GEN` / `IMG_EDIT`, or `ADMIN`), otherwise `403` (`ServiceRequestHandler#hasServicePermission`).
- Since v3.5 the legacy instance-level `allowedModels` whitelist has been removed; the whitelist recognizes **groups only**.

### Debug observability

When the debug switch is on, the response headers echo this routing decision (code: `ServiceRequestHandler` writes `X-Trunkline-*` headers):

| Response header | Meaning |
|---|---|
| `X-Trunkline-Instance` | The actually selected instance |
| `X-Trunkline-Pool` | The matched group name |
| `X-Trunkline-Strategy` | The load-balancing strategy used |

## Error Handling

### Error Response Format

```json
{
  "error": {
    "message": "Invalid request: missing required parameter 'model'",
    "type": "invalid_request_error",
    "code": "INVALID_REQUEST"
  }
}
```

### Common Error Types

| Error Type | Description |
|------------|-------------|
| `invalid_request_error` | 400 / 422 / other 4xx — request parameter error |
| `authentication_error` | 401 — authentication failed |
| `permission_error` | 403 — insufficient permissions |
| `not_found_error` | 404 — resource not found |
| `rate_limit_error` | 429 — rate limit / quota exceeded |
| `api_error` | every 5xx (including 503) — gateway or downstream internal error |

> These are the only values the gateway emits (`V1ErrorBodyMapper#errorTypeOf`); `overloaded_error` is never produced. This OpenAI-shaped body applies to `/v1/**`; `/api/**` keeps the `RouterResponse` shape (`success` / `message` / `data` / `errorCode` / `timestamp`) instead.

## Usage Examples

### cURL Examples

```bash
# Chat Completion
curl -X POST "http://localhost:8080/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{
    "model": "gpt-3.5-turbo",
    "messages": [
      {"role": "user", "content": "Hello!"}
    ]
  }'

# Text Embedding
curl -X POST "http://localhost:8080/v1/embeddings" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{
    "model": "text-embedding-ada-002",
    "input": "Text to embed"
  }'
```

### Python Examples

```python
import requests

# Configuration
base_url = "http://localhost:8080"
api_key = "your-api-key"
headers = {
    "Content-Type": "application/json",
    "X-API-Key": api_key
}

# Chat Completion
response = requests.post(
    f"{base_url}/v1/chat/completions",
    headers=headers,
    json={
        "model": "gpt-3.5-turbo",
        "messages": [
            {"role": "user", "content": "Hello!"}
        ]
    }
)
print(response.json())

# Text Embedding
response = requests.post(
    f"{base_url}/v1/embeddings",
    headers=headers,
    json={
        "model": "text-embedding-ada-002",
        "input": "Text to embed"
    }
)
print(response.json())
```

### JavaScript Examples

```javascript
const baseUrl = 'http://localhost:8080';
const apiKey = 'your-api-key';

// Chat Completion
async function chatCompletion() {
  const response = await fetch(`${baseUrl}/v1/chat/completions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': apiKey
    },
    body: JSON.stringify({
      model: 'gpt-3.5-turbo',
      messages: [
        { role: 'user', content: 'Hello!' }
      ]
    })
  });
  
  const data = await response.json();
  console.log(data);
}

// Text Embedding
async function embedding() {
  const response = await fetch(`${baseUrl}/v1/embeddings`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': apiKey
    },
    body: JSON.stringify({
      model: 'text-embedding-ada-002',
      input: 'Text to embed'
    })
  });
  
  const data = await response.json();
  console.log(data);
}
```