# 统一 API 接口

<!-- 版本信息 -->
> **文档版本**: 2.2.0
> **最后更新**: 2026-10-28
> **主要变更**: 以 Controller 为真相源核验——补录 `/v1/models`、`/v1/messages`、`/v1/messages/count_tokens`；修正 `/api/v1/images/edits` 为 multipart/form-data；新增「密钥与分组调用语义」章节
> **作者**: Lincoln
<!-- /版本信息 -->

## 重要变更说明 (v2.0.0)

### DTO 重构

为了提升 API 的可维护性和扩展性，我们对请求 DTO 进行了重构：

**重构前**：所有参数都在同一层级，导致参数字典臃肿（ChatDTO 有 56 个字段）

**重构后**：
- **核心字段**：只保留必需和常用参数（ChatDTO 11 个）
- **扩展选项**：适配器特定参数放入 `options` 对象中（ChatDTO 29 个）

**优势**：
- ✅ 核心参数清晰，便于快速上手
- ✅ 扩展参数按需使用，不影响简单场景
- ✅ 向后兼容，现有请求仍然有效
- ✅ 便于后续添加新参数

**使用示例**：
```json
{
  "model": "qwen3:4b",
  "messages": [{"role": "user", "content": "你好"}],
  "temperature": 0.7,
  "options": {
    "repeat_penalty": 1.1,
    "seed": 42
  }
}
```



Trunkline 提供兼容 OpenAI 格式的统一 API 接口，支持多种 AI 模型服务。OpenAI 兼容面在 `/v1` 下（另含 Anthropic 原生 `/v1/messages`）；TTS / STT / 图像接口**只在控制台面** `/api/v1/**` 暴露（见下表）。

## 两个入口面（v3.1 起）

| 入口面 | 路径 | 响应形态 | 用途 |
|---|---|---|---|
| **OpenAI 原生面** | `/v1/**` | 非流式：**原生 JSON**（`{id, object:"chat.completion", choices, usage}`）；流式：**原生 SSE**（`data: {...}`，以 `\n\n` 分隔） | OpenAI SDK / LangChain / LlamaIndex 直连：`base_url=http://<host>:8080/v1` |
| **Anthropic 原生面** | `/v1/messages`、`/v1/messages/count_tokens` | Anthropic Messages 原生形状（`{"type":"error","error":{"type","message"}}`） | Anthropic SDK 直连 |
| **控制台面** | `/api/v1/**` | 统一 `RouterResponse` 包裹（`{success, message, data}`） | Web 控制台 / AI 试验场内部调用；**不对外承诺 OpenAI 兼容** |

**鉴权约定**

- 网关凭据：`X-API-Key: <管理台创建的 API Key>`，或 `Jairouter_Token: <JWT>`。
- `Authorization` 头**保留并透传给下游 AI 服务**；**实例级 `headers` 优先级更高**——因此可以把下游密钥只配在实例上（`headers: {Authorization: "Bearer <下游密钥>"}`），客户端无需重复提供。
- 服务级权限：API Key 需具备 `CHAT` / `EMBEDDING` / `RERANK` 等服务权限（或 `ADMIN`）。
- 未携带凭据返回 `401`，权限不足返回 `403`，配额超限返回 `429`（带 `Retry-After` 与 `X-Quota-*`）。

> **v3.1 变更**：此前 `/v1/**` 由一个路径改写过滤器转发到 `/api/v1/**`，且转发后请求体丢失（恒返回 `400 Failed to read HTTP message`）。现已由 `OpenAiNativeController` 直接提供原生响应，`/v1` 才真正可作为 OpenAI SDK 的 `base_url`。

## 模型列表接口

### `GET /v1/models`

返回当前密钥/会话**可调用的模型分组（虚拟模型名）**，OpenAI 形状：

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

> 代码依据：`OpenAiNativeController#listModels` → `ModelCatalogService.listCallableModelsAsOpenAiList()`。v3.5 单线治理下，实例名（物理模型名）已不可对外直调（路由层关闭实例直调、密钥白名单只认分组），因此本接口只列分组。
>
> 控制台侧另有 `GET /api/models`（`ModelInfoController`），返回 `RouterResponse` 包裹的全量清单（分组 + 实例），供控制台内部使用，**不是** OpenAI 形状。

## 聊天完成接口

### `POST /v1/chat/completions`

处理聊天完成请求，支持流式和非流式响应。

#### 请求参数

```json
{
  "model": "gpt-3.5-turbo",
  "messages": [
    {
      "role": "system",
      "content": "你是一个有用的助手。"
    },
    {
      "role": "user",
      "content": "你好！"
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
  "user": "user-123",
  "options": {
    "repeat_penalty": 1.1,
    "seed": 42
  }
}
```

#### 参数说明

##### 核心参数

| 参数 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `model` | string | 是 | 要使用的模型名称 |
| `messages` | array | 是 | 对话消息列表 |
| `stream` | boolean | 否 | 是否启用流式响应，默认 false |
| `max_tokens` | integer | 否 | 生成的最大令牌数 |
| `temperature` | number | 否 | 采样温度，0-2 之间，默认 1 |
| `top_p` | number | 否 | 核采样参数，0-1 之间，默认 1 |
| `top_k` | integer | 否 | Top-K 采样参数 |
| `frequency_penalty` | number | 否 | 频率惩罚，-2 到 2 之间，默认 0 |
| `presence_penalty` | number | 否 | 存在惩罚，-2 到 2 之间，默认 0 |
| `stop` | string/array | 否 | 停止序列 |
| `user` | string | 否 | 用户标识符 |

##### 扩展参数（options 对象）

扩展参数包含在 `options` 对象中，按适配器类型分类：

**通用扩展参数**：

| 参数 | 类型 | 说明 |
|------|------|------|
| `options.n` | integer | 生成数量，默认 1 |
| `options.logprobs` | boolean | 是否返回 log 概率 |
| `options.top_logprobs` | integer | 返回的 top log 概率数 |
| `options.use_beam_search` | boolean | 是否使用束搜索 |
| `options.min_p` | number | 最小概率阈值 |
| `options.repetition_penalty` | number | 重复惩罚，默认 1.0 |
| `options.length_penalty` | number | 长度惩罚，默认 1.0 |
| `options.include_stop_str_in_output` | boolean | 是否在输出中包含停止符 |
| `options.ignore_eos` | boolean | 是否忽略 EOS 标记 |
| `options.min_tokens` | integer | 生成的最小 token 数 |
| `options.skip_special_tokens` | boolean | 是否跳过特殊 token |
| `options.add_generation_prompt` | boolean | 是否添加生成提示 |

**Ollama 特定参数**：

| 参数 | 类型 | 说明 |
|------|------|------|
| `options.repeat_penalty` | number | 重复惩罚，默认 1.1 |
| `options.seed` | integer | 随机种子 |
| `options.num_keep` | integer | 保留的 token 数 |
| `options.tfs_z` | number | 尾部自由采样参数 |
| `options.typical_p` | number | 典型采样参数 |
| `options.repeat_last_n` | integer | 重复检测窗口大小 |
| `options.penalize_newline` | boolean | 是否惩罚换行符 |

**GPUStack 特定参数**：

| 参数 | 类型 | 说明 |
|------|------|------|
| `options.documents` | array | 文档列表（用于 RAG） |
| `options.chat_template` | string | 聊天模板 |
| `options.chat_template_kwargs` | object | 聊天模板参数 |
| `options.structured_outputs` | object | 结构化输出配置 |
| `options.priority` | integer | 请求优先级 |
| `options.request_id` | string | 请求 ID |
| `options.cache_salt` | string | 缓存盐值 |

#### 消息格式

```json
{
  "role": "user|assistant|system",
  "content": "消息内容",
  "name": "可选的消息发送者名称"
}
```

#### 响应格式

**非流式响应：**

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
        "content": "你好！我是一个AI助手，很高兴为你服务。"
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

**流式响应：**

```json
data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1677652288,"model":"gpt-3.5-turbo","choices":[{"index":0,"delta":{"role":"assistant","content":"你好"},"finish_reason":null}]}

data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1677652288,"model":"gpt-3.5-turbo","choices":[{"index":0,"delta":{"content":"！"},"finish_reason":null}]}

data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1677652288,"model":"gpt-3.5-turbo","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

## 文本嵌入接口

### `POST /v1/embeddings`

将文本转换为向量表示，用于语义搜索、相似度计算等任务。

#### 请求参数

```json
{
  "model": "text-embedding-ada-002",
  "input": "要嵌入的文本内容",
  "encoding_format": "float",
  "dimensions": 1536,
  "user": "user-123"
}
```

#### 参数说明

| 参数 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `model` | string | 是 | 嵌入模型名称 |
| `input` | string/array | 是 | 要嵌入的文本，可以是字符串或字符串数组 |
| `encoding_format` | string | 否 | 编码格式，支持 "float" 或 "base64" |
| `dimensions` | integer | 否 | 输出向量的维度 |
| `user` | string | 否 | 用户标识符 |

#### 响应格式

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

## 重排序接口

### `POST /v1/rerank`

对文档列表进行重新排序，根据查询的相关性进行排名。

#### 请求参数

```json
{
  "model": "rerank-multilingual-v2.0",
  "query": "什么是人工智能？",
  "documents": [
    "人工智能是计算机科学的一个分支",
    "机器学习是人工智能的子领域",
    "深度学习使用神经网络"
  ],
  "top_n": 3,
  "return_documents": true
}
```

#### 参数说明

| 参数 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `model` | string | 是 | 重排序模型名称 |
| `query` | string | 是 | 查询文本 |
| `documents` | array | 是 | 要排序的文档列表 |
| `top_n` | integer | 否 | 返回的文档数量 |
| `return_documents` | boolean | 否 | 是否返回文档内容 |

#### 响应格式

```json
{
  "id": "rerank-123",
  "results": [
    {
      "index": 0,
      "score": 0.95,
      "document": "人工智能是计算机科学的一个分支"
    },
    {
      "index": 1,
      "score": 0.87,
      "document": "机器学习是人工智能的子领域"
    }
  ],
  "model": "rerank-multilingual-v2.0",
  "usage": {
    "total_tokens": 25
  }
}
```

## Anthropic Messages 接口

### `POST /v1/messages`

以 Anthropic Messages 原生形状提供聊天完成服务（代码依据：`AnthropicMessagesController`，路由到 chat 服务池）。

#### 请求参数

```json
{
  "model": "chat-groups",
  "max_tokens": 1024,
  "system": "你是一个有用的助手。",
  "messages": [
    {"role": "user", "content": "你好！"}
  ],
  "stream": false,
  "temperature": 0.7
}
```

| 参数 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `model` | string | 是 | 模型分组名（同 OpenAI 面，必须是分组而非实例名） |
| `messages` | array | 是 | Anthropic 消息列表（`role` / `content`） |
| `max_tokens` | integer | 是 | 生成的最大令牌数 |
| `system` | string | 否 | 系统提示 |
| `stream` | boolean | 否 | 是否流式（SSE），默认 false |
| `temperature` | number | 否 | 采样温度 |

#### 响应格式

非流式为 Anthropic 形状（`id` / `type:"message"` / `role` / `content` / `model` / `stop_reason` / `usage`）；`stream:true` 时返回 Anthropic 事件流 SSE。

> **错误体差异**：`/v1/messages*` 的错误响应是 Anthropic 形状 `{"type":"error","error":{"type","message"}}`——**没有 `code` 字段**；而其他 `/v1/**` 错误是 OpenAI 形状 `{"error":{"message","type","code"}}`（代码依据：`V1ErrorBodyMapper#toBody`）。

### `POST /v1/messages/count_tokens`

统计 `messages` 的 token 数（代码依据：`AnthropicMessagesController#countTokens`），请求体与 `/v1/messages` 相同（无需 `max_tokens`），返回 Anthropic 计数形状（含 `input_tokens`）。该接口为**本地估算、不触达下游模型**。

> 鉴权与其他 `/v1/**` 一致：`X-API-Key` 或 `Jairouter_Token`。

## 文本转语音接口

### `POST /api/v1/audio/speech`

> **仅控制台面**：`/v1` 面注册了 `GET /v1/models`、`POST /v1/chat/completions`、`POST /v1/embeddings`、`POST /v1/rerank`、`POST /v1/messages`、`POST /v1/messages/count_tokens`，本端点没有 `/v1/...` 映射。

将文本转换为语音音频文件。

#### 请求参数

```json
{
  "model": "tts-1",
  "input": "你好，这是一段测试语音。",
  "voice": "alloy",
  "response_format": "mp3",
  "speed": 1.0
}
```

#### 参数说明

| 参数 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `model` | string | 是 | TTS 模型名称 |
| `input` | string | 是 | 要转换的文本 |
| `voice` | string | 是 | 语音类型 |
| `response_format` | string | 否 | 音频格式，默认 mp3 |
| `speed` | number | 否 | 语音速度，0.25-4.0，默认 1.0 |

#### 响应格式

返回音频文件的二进制数据，Content-Type 根据 `response_format` 设置。

## 语音转文本接口

### `POST /api/v1/audio/transcriptions`

> **仅控制台面**：`/v1` 面注册了 `GET /v1/models`、`POST /v1/chat/completions`、`POST /v1/embeddings`、`POST /v1/rerank`、`POST /v1/messages`、`POST /v1/messages/count_tokens`，本端点没有 `/v1/...` 映射。

将音频文件转换为文本。

#### 请求参数

使用 `multipart/form-data` 格式：

```
file: (音频文件)
model: whisper-1
language: zh
prompt: (可选提示词)
responseFormat: json
temperature: 0
```

#### 参数说明

| 参数 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `file` | file | 是 | 音频文件 |
| `model` | string | 是 | STT 模型名称 |
| `language` | string | 否 | 音频语言代码 |
| `responseFormat` | string | 否 | 响应格式，默认 json |
| `temperature` | number | 否 | 采样温度 |

#### 响应格式

```json
{
  "text": "转录的文本内容"
}
```

## 图像生成接口

### `POST /api/v1/images/generations`

> **仅控制台面**：`/v1` 面注册了 `GET /v1/models`、`POST /v1/chat/completions`、`POST /v1/embeddings`、`POST /v1/rerank`、`POST /v1/messages`、`POST /v1/messages/count_tokens`，本端点没有 `/v1/...` 映射。

根据文本描述生成图像。

#### 请求参数

```json
{
  "model": "dall-e-3",
  "prompt": "一只可爱的小猫在花园里玩耍",
  "n": 1,
  "size": "1024x1024",
  "quality": "standard",
  "response_format": "url",
  "style": "vivid",
  "user": "user-123"
}
```

#### 参数说明

| 参数 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `model` | string | 是 | 图像生成模型名称 |
| `prompt` | string | 是 | 图像描述文本 |
| `n` | integer | 否 | 生成图像数量，默认 1 |
| `size` | string | 否 | 图像尺寸，默认 1024x1024 |
| `quality` | string | 否 | 图像质量，standard 或 hd |
| `response_format` | string | 否 | 响应格式，url 或 b64_json |
| `style` | string | 否 | 图像风格，vivid 或 natural |
| `user` | string | 否 | 用户标识符 |

#### 响应格式

```json
{
  "created": 1677652288,
  "data": [
    {
      "url": "https://example.com/image.png",
      "revised_prompt": "修订后的提示词"
    }
  ]
}
```

## 图像编辑接口

### `POST /api/v1/images/edits`

> **仅控制台面**：`/v1` 面注册了 `GET /v1/models`、`POST /v1/chat/completions`、`POST /v1/embeddings`、`POST /v1/rerank`、`POST /v1/messages`、`POST /v1/messages/count_tokens`，本端点没有 `/v1/...` 映射。

编辑现有图像。

#### 请求参数

**`multipart/form-data`** 请求（代码依据：`UniversalController#editImages` 现声明 `consumes = MediaType.MULTIPART_FORM_DATA_VALUE`，与 STT 一致；旧的 `@RequestBody ImageEditDTO.Request` JSON 写法会得到 `415 Unsupported Media Type`）：

```
image: (图片文件，可多份)
prompt: 编辑描述
model: dall-e-2
n: 1
size: 1024x1024
response_format: url
user: user-123
```

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `image` | file | 是 | 待编辑图片文件（可多份） |
| `prompt` | string | 是 | 编辑描述 |
| `model` | string | 是 | 图像编辑模型分组名 |
| `n` | integer | 否 | 生成数量，默认 1 |
| `size` | string | 否 | 图像尺寸，默认 1024x1024 |
| `response_format` | string | 否 | 响应格式，url 或 b64_json |
| `user` | string | 否 | 用户标识符 |

> 对照：`/api/v1/audio/transcriptions` 同样是 `multipart/form-data`，其部件名 `responseFormat` 为驼峰。

#### 响应格式

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

## 密钥与分组调用语义（`model` 字段如何被路由）

本章只讲**请求侧** `model` 字段如何解析到模型分组（模型池）并受密钥约束；密钥的创建/轮换/配额管理本身见安全板块。

### 核心规则：`model` 必须是「模型分组名」

v3.5 单线治理下，`model` 字段**只接受模型分组名（pool 名，即对外虚拟模型名）**；实例名（物理模型名）已不可对外直调。

解析顺序（代码依据：`ModelServiceRegistry#selectInstance` → `PoolSelector#findPool`）：

1. `model` 为空 → `400`（`IllegalArgumentException`）。
2. **规则引擎先评估**（v2.8.5 起）：管理员侧配置的路由规则可能改写目标模型（`TARGET_MODEL`）、锁定实例（`TARGET_INSTANCE`）、覆盖负载均衡策略、触发限流（`RATE_LIMIT`）——这是控制台配置，**客户端不可控**。
3. **标签过滤**：请求头 `X-JAiRouter-Tags`（或规则 `TARGET_TAGS`）从候选实例中按标签过滤。
4. **分组精确匹配**：在当前端点决定的服务类型（`chat` / `embedding` / `rerank` / `tts` / `stt` / `imgGen` / `imgEdit`）下，按字符串精确比较已启用分组名（`pool.getPoolName().equals(modelName)`）。命中 → 以该分组的成员为候选实例，并使用该分组配置的负载均衡策略。
5. **系统分组 `auto-model`**：内置虚拟分组名，等于当前服务类型下的全部健康实例。
6. **都不命中 → `404`**，message 形如：`Model 'X' is not a model group. Requests must target a model group (pool); direct model access is not allowed.`

分组命中但候选实例为空（无健康成员）→ **`503`**（`PoolUnavailableException`），响应带 `Retry-After: 60` 与各成员不可用原因。

### 不存在「pool/model」锁定语法

代码中**没有**任何对 `model` 字段做 `/` 切分的逻辑——`model` 整体作为分组名做精确字符串匹配，不支持 `pool/model`、`group/instance` 之类的写法。要让请求落到指定实例，只能由管理员在规则引擎中配置 `TARGET_INSTANCE`，或开启调试后从响应头观察实际选中实例。

### 密钥白名单对 `model` 解析的约束

API Key 的 `allowedGroups` 字段（模型分组白名单，代码依据：`auth/security/config/properties/ApiKey.java`）：

| `allowedGroups` | 行为 |
|---|---|
| `null` / 空 | **不限制**（兼容历史）：该密钥可调用其服务权限内的任意分组 |
| 非空 | 请求的 `model` 必须 `allowedGroups.contains(modelName)`，否则 **`403`** |

代码依据：`ServiceRequestHandler#hasModelPermission`（`allowedGroups == null || isEmpty() → true`；否则 `allowedGroups.contains(modelName)`）。

要点：

- 该白名单**只对 API Key 调用方生效**；控制台 JWT 调用方豁免模型白名单检查（`hasModelPermission` 内 `caller.isJwtCaller() → return true`）。
- 服务级权限是另一道闸门：API Key 需持有对应服务角色（`CHAT` / `EMBEDDING` / `RERANK` / `TTS` / `STT` / `IMG_GEN` / `IMG_EDIT`，或 `ADMIN`），否则 `403`（`ServiceRequestHandler#hasServicePermission`）。
- v3.5 起旧的实例级 `allowedModels` 白名单已移除，白名单**只认分组**。

### 调试观测

调试开关开启时，响应头会回显本次路由决策（代码依据：`ServiceRequestHandler` 写 `X-Trunkline-*` 头）：

| 响应头 | 含义 |
|---|---|
| `X-Trunkline-Instance` | 实际选中的实例 |
| `X-Trunkline-Pool` | 命中的分组名 |
| `X-Trunkline-Strategy` | 本次使用的负载均衡策略 |

## 错误处理

### 错误响应格式

```json
{
  "error": {
    "message": "Invalid request: missing required parameter 'model'",
    "type": "invalid_request_error",
    "code": "INVALID_REQUEST"
  }
}
```

### 常见错误类型

| 错误类型 | 说明 |
|----------|------|
| `invalid_request_error` | 400 / 422 / 其他 4xx —— 请求参数错误 |
| `authentication_error` | 401 —— 认证失败 |
| `permission_error` | 403 —— 权限不足 |
| `not_found_error` | 404 —— 资源不存在 |
| `rate_limit_error` | 429 —— 限流 / 配额超限 |
| `api_error` | 所有 5xx（含 503）—— 网关或下游内部错误 |

> 以上是网关实际只会产出的取值（`V1ErrorBodyMapper#errorTypeOf`）；`overloaded_error` 不会被产出。该 OpenAI 形状的错误体只适用于 `/v1/**`；`/api/**` 沿用 `RouterResponse` 形状（`success` / `message` / `data` / `errorCode` / `timestamp`）。

## 使用示例

### cURL 示例

```bash
# 聊天完成
curl -X POST "http://localhost:8080/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{
    "model": "gpt-3.5-turbo",
    "messages": [
      {"role": "user", "content": "你好！"}
    ]
  }'

# 文本嵌入
curl -X POST "http://localhost:8080/v1/embeddings" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{
    "model": "text-embedding-ada-002",
    "input": "要嵌入的文本"
  }'
```

### Python 示例

```python
import requests

# 配置
base_url = "http://localhost:8080"
api_key = "your-api-key"
headers = {
    "Content-Type": "application/json",
    "X-API-Key": api_key
}

# 聊天完成
response = requests.post(
    f"{base_url}/v1/chat/completions",
    headers=headers,
    json={
        "model": "gpt-3.5-turbo",
        "messages": [
            {"role": "user", "content": "你好！"}
        ]
    }
)
print(response.json())

# 文本嵌入
response = requests.post(
    f"{base_url}/v1/embeddings",
    headers=headers,
    json={
        "model": "text-embedding-ada-002",
        "input": "要嵌入的文本"
    }
)
print(response.json())
```

### JavaScript 示例

```javascript
const baseUrl = 'http://localhost:8080';
const apiKey = 'your-api-key';

// 聊天完成
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
        { role: 'user', content: '你好！' }
      ]
    })
  });
  
  const data = await response.json();
  console.log(data);
}

// 文本嵌入
async function embedding() {
  const response = await fetch(`${baseUrl}/v1/embeddings`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': apiKey
    },
    body: JSON.stringify({
      model: 'text-embedding-ada-002',
      input: '要嵌入的文本'
    })
  });
  
  const data = await response.json();
  console.log(data);
}
```