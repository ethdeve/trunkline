# Trunkline 多副本部署基线（共享态自检）

> 多副本部署下，配额限额、令牌/密钥吊销等能力依赖「跨副本共享态」。
> 若以下基线未满足，多副本现场会静默放大配额、吊销不跨副本生效——单副本测试全绿，多副本现场才暴露。
> 本文解决「**如何把多副本部署做对**」，不引入任何商业逻辑，属于开源 + 部署边界
> （商业版多租户等能力同样依赖此基线，商业侧附加约束见商业版部署文档）。

---

## 0. 一句话原则

**副本数必须显式声明，共享态开关必须按副本数打开，否则生产启动即失败（fail-fast）。**
不要依赖 K8s 环境自动探测来"碰运气"——私有化客户非 K8s 占比不低，且单副本 K8s Pod 会被误判多副本。

---

## 1. 副本数显式声明（`trunkline.cluster.replicas`）

| 取值 | 含义 | 行为 |
|---|---|---|
| `1` | 显式单副本（**生产默认**） | 跳过共享态自检，5 个开关关着也正常 |
| `>1` | 多副本 | 触发共享态自检，开关未开则按 `mode` 处置 |
| `0` / 未声明 | 未知，回退环境探测 | **禁止用于生产**：K8s 里 `KUBERNETES_SERVICE_HOST`/`POD_NAME` 存在即判多副本，会把单副本 K8s Pod 误判、在 `FAIL` 模式下启动失败 |

生产 `application-prod.yml` 已将其默认值收紧为 `1`：

```yaml
trunkline:
  cluster:
    replicas: ${TRUNKLINE_REPLICAS:1}   # 多副本部署必须显式 export TRUNKLINE_REPLICAS=N
```

> 历史默认值 `0` 的坑：单副本 K8s Pod 因注入的 `KUBERNETES_SERVICE_HOST` 被误判多副本，在 `mode=FAIL` 下直接启动失败。已修正为默认 `1`。

---

## 2. 五个共享态开关后果对照表

多副本下，下列开关任一未开启，对应能力即退化为「各副本互不知情的单机实例」：

| 配置键 | 未开启的后果 |
|---|---|
| `trunkline.security.jwt.blacklist.redis.enabled` | 副本 A 吊销的令牌，副本 B 仍放行（**安全事件**） |
| `trunkline.security.jwt.persistence.redis.enabled` | 令牌状态各副本独立，重启后可能丢失已签发状态 |
| `trunkline.security.cache.redis.enabled` | API Key 缓存各副本独立，吊销/新建不跨副本生效 |
| `trunkline.quota.distributed.enabled` | 配额计数每副本一份，实际放行量 ≈ 配置值 × 副本数（**最严重：闸门失效，账仍准但拦不住**） |
| `trunkline.persistence.redis.enabled` | 状态持久化退坡到本机 H2/文件，不做跨节点共享 |

> 这 5 项即自检门闩检查的完整清单（`MultiReplicaSharedStateGate.SHARED_STATE_SWITCHES`），与代码同源，文档不会过时。

---

## 3. 自检门闩 `shared-state-check.mode` 三档语义

生产 `application-prod.yml` 默认 `FAIL`。

| 模式 | 多副本 + 开关缺失时 | 适用 |
|---|---|---|
| `OFF` | 不检查 | 仅临时排错，正式环境禁用 |
| `WARN` | 启动告警 + 日志（默认，不改变 `/actuator/health`） | 开发/灰度观察 |
| `FAIL` | **启动失败**（`SpringApplication.run` 抛异常，不对外服务） | **生产环境** |

配套开关：
- `trunkline.cluster.shared-state-check.enabled`：总开关，默认 `true`。
- `trunkline.cluster.shared-state-check.fail-health`：WARN 模式下是否把 `/actuator/health` 置 `DOWN`，默认 `false`（避免 K8s 重启循环）。

自检在 `ApplicationReadyEvent` 执行，基于最终绑定值；`FAIL` 抛出的异常会终止启动，应用不会带病上线。

---

## 4. 多副本部署清单模板

多副本部署时，在启动环境（K8s Secret / systemd env / compose env）中补齐：

```bash
# 副本数（必须 >1 才会触发共享态自检）
export TRUNKLINE_REPLICAS=3

# 五个共享态开关（Redis 必须可达，否则开关虽开但运行时连接失败）
export TRUNKLINE_SECURITY_JWT_BLACKLIST_REDIS_ENABLED=true
export TRUNKLINE_SECURITY_JWT_PERSISTENCE_REDIS_ENABLED=true
export TRUNKLINE_SECURITY_CACHE_REDIS_ENABLED=true
export TRUNKLINE_QUOTA_DISTRIBUTED_ENABLED=true
export TRUNKLINE_PERSISTENCE_REDIS_ENABLED=true

# Redis 连接（各共享态开关的实际后端）
export REDIS_HOST=redis.internal
export REDIS_PORT=6379
export REDIS_PASSWORD=****        # 经密钥管理注入，勿落明文
```

等效的 `application-prod.yml` 片段（任选其一，env 优先）：

```yaml
trunkline:
  cluster:
    replicas: 3
    shared-state-check:
      mode: FAIL
  security:
    jwt:
      blacklist:
        redis: { enabled: true }
      persistence:
        redis: { enabled: true }
    cache:
      redis: { enabled: true }
  quota:
    distributed: { enabled: true }
  persistence:
    redis: { enabled: true }
spring:
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}
      password: ${REDIS_PASSWORD}
```

> 不推荐把 5 个开关在 `application-prod.yml` 全局写死为 `true`：会强制单副本 prod 也依赖 Redis，无 Redis 的单副本生产将启动失败或运行时报错。正确做法是**仅多副本部署经 env/清单开启**，由 `mode=FAIL` 强制保证不遗漏。

---

## 5. 调用记录密钥分发约束（O4）

- `RecordContentCipher` 经 `TRUNKLINE_CALL_HISTORY_KEY` 注入 AES-256 密钥；多副本**各副本必须同 env 值**，否则 A 副本加密的调用记录在 B 副本无法解密。
- 共享卷首启动竞态（两副本同时生成、后写覆盖）在禁 auto（缺失即启动失败）后天然消除。
- 密钥经密钥管理（Vault / K8s Secret）统一注入，勿落明文、勿各副本各异。

---

## 6. 验收清单（唯一能拦住配额放大的测试，无需改代码即可执行）

- [ ] 单租户 1000 次/日限额，**3 副本压测实际放行 ≤ 1000**（现状必然 ~3000，此测试直接暴露）。
- [ ] 副本 A 吊销 API Key / 登出令牌，**副本 B 立即拒绝**。
- [ ] **裸机双进程 + 5 开关未开 + `TRUNKLINE_REPLICAS=2`**：启动自检 **FAIL**（fail-fast 生效）。
- [ ] `TRUNKLINE_REPLICAS` 未设（默认 `1`）的单副本 K8s Pod：正常启动，无误判告警。
- [ ] 调用记录密钥各副本同值。

---

## 7. 常见误配排错

| 现象 | 原因 | 修复 |
|---|---|---|
| 多副本启动直接失败，日志含「检测到多副本部署，但以下跨副本共享态开关未开启」 | 开关未开 + `mode=FAIL` | 按第 4 节补齐 `TRUNKLINE_*_REDIS_ENABLED` 与 `REDIS_*` |
| 单副本 K8s Pod 启动失败（同样报错） | `replicas` 被回退 K8s 探测误判 | 显式 `export TRUNKLINE_REPLICAS=1` |
| 配额被放大约 N 倍 | `quota.distributed.enabled` 未开 | 开启该开关并确认 Redis 可达 |
| 副本 A 吊销的令牌副本 B 仍可用 | `security.jwt.blacklist.redis.enabled` 未开 | 开启并确认 Redis 可达 |

---

## 8. 许可证部署与功能位解锁（闸门④）

> 已落码能力（SSO / 多租户 / 计费 / 审核）默认**全部关闭**，由 license 的 `features[]` 经由
> `LicenseEnvironmentPostProcessor`（A0 EPP，最高优先级）在启动时注入 `trunkline.license.feature.*`
> 属性驱动。没有签发带功能位的 license 并放到正确位置，企业 jar 在场也不会解锁任何能力。
> 本节能把「签发 → 部署 → 验证」一条链做对。

### 8.1 签发带功能位的 license

使用独立的 A1 签发工具（`trunkline-license-tool`，私钥离线、绝不入库）：

```bash
# 签发含全部 4 个运行时能力的 license（edition=enterprise，5 租户，1 年有效）
./run.sh sign --edition enterprise --max-tenants 5 --serial <单调序列号> \
              --valid-until-days 365 --features sso,tenant,billing,moderation \
              --out license.json
```

- `features` 取值与运行时映射对齐：`sso` / `tenant` / `billing` / `moderation` 各自解锁一个
  `trunkline.license.feature.<name>` 属性并触发对应 SPI Bean 注册；`auditChain` / `dlp` 为预留位。
- 工具对**未知功能位名**打印 `WARN`（拼写错误会导致能力静默不解锁），加 `--strict` 直接退出码 2 禁止签发。
- `serial` 必须**单调递增**（防回滚攻击）：续期/增发新 license 时取比旧值更大的数。

### 8.2 放置与启动参数

| 配置项 | 环境变量 / 默认值 | 说明 |
|---|---|---|
| 许可证路径 | `TRUNKLINE_ENTERPRISE_LICENSE_LOCATION`（默认 `file:/etc/trunkline/license.json`） | 各副本**必须挂载同一份** license 文件（同 `serial`、同 `features`） |
| 是否强制 | `TRUNKLINE_ENTERPRISE_LICENSE_ENFORCE`（默认 `true`） | `false` 时缺失/无效许可证降级社区版、不解锁任何企业能力；生产必须为 `true` |
| 本地状态路径 | `TRUNKLINE_ENTERPRISE_LICENSE_STATE_PATH` | **多副本必须指向共享持久卷（PVC/NFS）**，否则各副本独立记录 `serial` 与宽限锚点，pod 重建即丢失防回滚/72h 宽限 |

> 多副本场景下，所有副本共享同一份 license 文件与同一份 `license.state` 卷，是「跨副本吊销 / 防回滚」成立的前提。
> 单副本（含 K8s 单 Pod）可保持默认本地 `~/.trunkline/license.state`。

### 8.3 功能位解锁链路（签发 → 运行时）

```
A1 工具签发 {features:[sso,tenant,billing,moderation]}
   │  SHA256withRSA 离线私钥签名
   ▼
/etc/trunkline/license.json
   │  LicenseEnvironmentPostProcessor（最高优先级 EPP）验签
   ▼
注入 trunkline.license.feature.{sso,tenant,billing,moderation}=true  ── addFirst，yml 无法覆盖
   │  @ConditionalOnProperty(prefix="trunkline.license.feature") 命中
   ▼
4 个企业 SPI Bean 注册（@ConditionalOnMissingBean 覆盖开源 Noop）：
  EnterpriseSsoConnector / EnterpriseTenantContext / EnterpriseBillingProvider / EnterpriseModerationProvider
```

### 8.4 闸门④ 验收清单

- [ ] 用 A1 工具签发含 `sso,tenant,billing,moderation` 的 license，能成功产出信封 JSON（`verify` 子命令验签通过）。
- [ ] 放置 license 后应用启动无「许可证缺失/无效」报错；`/actuator/health` 不因此变 `DOWN`。
- [ ] **企业 jar 在场 + 4 功能位全开** → 4 个 SPI Bean 注册企业实现（`EnterpriseAutoConfigurationTest.enterpriseBeansRegisterWhenFeatureBitsEnabled` 已断言，CI 绿即证明）。
- [ ] **无功能位 / 仅部分功能位** → 未解锁能力退位开源 Noop，绝不因「降级」误解锁（同测试 `noFeatureBitsFallsBackToNoop` / `partialFeatureBitsRegisterSubset`）。
- [ ] 多副本部署：各副本 `license.json` 与 `license.state` 卷内容一致；任一副本吊销 API Key / 令牌，其余副本立即拒绝（依赖第 2~4 节的 5 个共享态开关 + `TRUNKLINE_REPLICAS=N`）。
- [ ] license `serial` 回退（拿旧 license 顶替）→ 启动失败（`序列号回滚`，`LicenseEnvironmentPostProcessorTest.serialRollbackRejectsStartup` 已覆盖）。

---

*基线机制实现：`org.unreal.modelrouter.common.cluster.MultiReplicaSharedStateChecker` / `MultiReplicaSharedStateGate` / `SharedStateHealthIndicator`。*
*自检逻辑单测：`src/test/java/org/unreal/modelrouter/common/cluster/MultiReplicaSharedStateGateTest`。*
