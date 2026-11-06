# 模型分组（资源池）

<!-- 版本信息 -->
> **文档版本**: 1.0.0  
> **最后更新**: 2026-10-28
> **作者**: Lincoln
<!-- /版本信息 -->

## 概述

模型分组（资源池 / Model Pool）把一组**同服务类型**的实例打包成一个命名集合。客户端请求中的 `model` 字段填**分组名**时，网关自动在组内健康实例之间分流选择，客户端无需关心后端具体实例。

- 配置文件：`config/router/pools.yml`，绑定前缀 `model.pools`（默认空列表 `[]`，不配池时路由行为与之前完全一致）
- 持久化：Web 管理端创建/修改的池持久化到 StoreManager（key=`pool_definitions`），与 YAML 合并（同 `pool-name` 时持久化配置覆盖 YAML）
- 特殊组名 `auto-model`：未配置同名池时，`model=auto-model` 回退为该服务类型的**全部健康实例**；配置同名池后按池定义选择

## 配置结构

```yaml
# config/router/pools.yml
model:
  pools:
    - pool-name: auto-model        # 分组名（即客户端请求 model 字段填写的虚拟模型名）
      name: 默认自动分流池            # 展示名称
      service-type: chat            # 服务类型（chat/embedding/rerank/tts/stt/imgGen/imgEdit）
      enabled: true                 # 是否启用
      strategy: weighted-random     # 组内选择策略（见下表）
      members:                      # 组成员（按实例 ID 引用）
        - instance-id: inst-gpt
          weight: 9                 # 组级权重（默认 1）
          model-name: gpt-4o        # 可选：响应回显的模型名（缺省用实例名）
        - instance-id: inst-claude
          weight: 1
```

### 字段说明

| 字段 | 必填 | 说明 |
|------|------|------|
| `pool-name` | 是 | 分组唯一标识；客户端请求 `model=<pool-name>` 即命中该组路由 |
| `name` | 否 | 展示名称 |
| `service-type` | 是 | 服务类型；组内成员必须同属该服务类型 |
| `enabled` | 否 | 是否启用，默认 `true` |
| `strategy` | 否 | 组内选择策略，默认 `weighted-random` |
| `members[].instance-id` | 是 | 引用实例管理中的 `instanceId`；成员实例被删除时自动跳过（告警日志） |
| `members[].weight` | 否 | 组级权重（默认 1）；**覆盖**实例自身的 `weight` |
| `members[].model-name` | 否 | 响应回显的模型名（缺省用实例名） |

### 组内选择策略

| 策略 | 说明 |
|------|------|
| `weighted-random` | 权重随机（默认），按组级 weight 加权 |
| `round-robin` | 轮询 |
| `least-connections` | 最少活跃连接 |
| `ip-hash` | 客户端 IP 哈希会话粘性 |
| `consistent-hash` | 一致性哈希（控制器侧接受，忽略权重） |

> 组内选择与全局负载均衡链路一致：先做**健康/熔断过滤**（被熔断或不健康的实例不参与选择），再按组策略选择；组级权重覆盖实例权重。

## 调用语义

### 自动分流：`model` 字段 = 分组名

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "auto-model",
    "messages": [{"role": "user", "content": "你好"}]
  }'
```

- `model` 填分组名（如 `auto-model`）→ 网关解析到池，在组内健康实例间按 `strategy` 分流
- `model` 填普通实例名 → 走原有实例匹配逻辑，不受池影响
- 未配置任何池时，`auto-model` 回退为该服务全部健康实例

### 与路由规则联动

路由规则 `TARGET_MODEL` 动作的目标可以直接填分组名；规则命中后即走该组路由（见 [路由规则](routing-rules.md)）。

### 响应回显

池路由后，响应与出站请求的 `model` 字段为实际选中实例的模型名（非流式直接改写；流式出站请求同步改写）。

## 管理 API

基路径：`/api/config/pools`

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/config/pools/list` | GET | 获取全部分组列表 |
| `/api/config/pools/{poolName}` | GET | 获取单个分组定义 |
| `/api/config/pools` | POST | 创建分组（`poolName` 必填，字段含 members 列表与配额字段） |
| `/api/config/pools/{poolName}` | PUT | 更新分组 |
| `/api/config/pools/{poolName}` | DELETE | 删除分组 |
| `/api/config/pools/{poolName}/quota/usage` | GET | 查看分组配额用量 |
| `/api/config/pools/{poolName}/quota/reset` | POST | 重置分组配额计数 |

### 创建分组示例

```bash
curl -X POST http://localhost:8080/api/config/pools \
  -H "Content-Type: application/json" \
  -d '{
    "poolName": "auto-model",
    "name": "默认自动分流池",
    "serviceType": "chat",
    "enabled": true,
    "strategy": "weighted-random",
    "members": [
      {"instanceId": "inst-gpt", "weight": 9},
      {"instanceId": "inst-claude", "weight": 1}
    ]
  }'
```

## 分组级配额

分组可携带一套**共享配额**（组内所有 API Key 共用同一预算），支持分钟/小时/天/月四个窗口 × 请求数/Token 两个维度：

| 字段 | 说明 |
|------|------|
| `rateLimitPerMinute` | 每分钟请求上限（0 = 不限） |
| `hourRequests` / `hourTokens` | 每小时请求数 / Token 上限 |
| `dayRequests` / `dayTokens` | 每天请求数 / Token 上限 |
| `monthRequests` / `monthTokens` | 每月请求数 / Token 上限 |
| `quotaAlertThreshold` | 告警阈值比例（默认 0.8，即用量达 80% 告警） |

- 配额维度 ID：`group_id = 分组名`
- 用量查询：`GET /api/config/pools/{poolName}/quota/usage`
- 手动重置：`POST /api/config/pools/{poolName}/quota/reset`

> 分组配额属于运行期配额账本体系；若同时启用多维配额账本（`jairouter.quota.enabled`），分组维度为其中的一种聚合视图。详见 [配额配置](quota.md)。

## 已知边界

- 组成员按 `instance-id` 引用实例，实例被删除后该成员自动跳过（告警日志），不自动替换
- 分组策略与全局 `model.load-balance.type` 相互独立，组内选择以池 `strategy` 为准
- 组选出的实例继续走既有的服务级/实例级限流与熔断链路

## 相关文档

- [路由规则](routing-rules.md) - TARGET_MODEL 可直接填分组名
- [限流配置](rate-limiting.md) - 组内实例的限流链路
- [配额配置](quota.md) - 多维配额账本
