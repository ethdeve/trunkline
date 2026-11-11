# Trunkline 脚本目录

本目录包含项目所有的脚本文件，按功能分类管理。

## 目录结构

```
scripts/
├── build/          # 构建部署脚本
├── dev/            # 开发辅助脚本
├── monitoring/     # 监控部署脚本
└── test/           # 测试脚本
```

## 脚本分类说明

### build/ - 构建部署

| 脚本 | 说明 |
|------|------|
| `build-and-deploy.sh` | 标准构建部署脚本，完整的构建部署流程 |
| `docker-build.sh/ps1` | Docker 镜像构建 |
| `docker-build-china.sh/ps1` | Docker 镜像构建（国内镜像加速） |
| `docker-run.sh/ps1` | Docker 容器运行 |
| `package-desktop-windows.ps1` | Windows 桌面便携版（app-image + zip，内置 JRE，双击 exe 即用）。**须以 UTF-8 BOM 保存**，否则 PowerShell 5.1 下中文 here-string 解析失败 |
| `package-desktop-linux.sh` | Linux 桌面便携版（tar.gz，内置 JRE），支持 `--arch x64\|aarch64` |
| `package-desktop-mac.sh` | macOS 桌面便携版（dmg + tar.gz，内置 JRE，start.command 启动），支持 `--arch arm64\|x64` |

> 三平台桌面脚本统一思路：内置 jlink 宽模块 runtime（避 jpackage 自动裁剪漏 `java.naming`）+ 启动脚本，数据落解压目录的 `./data`（可写、免 root、不改 Java），自动开浏览器由 Java 层 `DesktopBrowserLauncher` 负责。runtime 架构跟随构建机 JDK，跨架构需在对应机器上构建。用法见各脚本头部注释或 `./scripts/build/package-desktop-linux.sh --help`。

### dev/ - 开发辅助

| 脚本 | 说明 |
|------|------|
| `run-dev.sh/ps1` | 本地快速构建并前台运行（默认端口 31080，可用 `$1`/`-Port` 改），非 Docker 的轻量替代 |
| `diagnose-db-lock.ps1` | 诊断并解除 H2 文件库被残留后端进程占用的启动失败（`-Kill` 结束占用进程） |
| `start-test-instance.ps1` | 启动与 IDEA 完全隔离的测试实例（默认 8081 + 独立测试库），规避 H2 单 JVM 文件锁 |

### monitoring/ - 监控部署

| 脚本 | 说明 |
|------|------|
| `setup-monitoring.sh/ps1` | 监控栈一键部署（Prometheus、Grafana 等） |
| `run-monitoring-tests.sh/ps1` | 监控系统集成测试 |

### test/ - 测试脚本

| 脚本 | 说明 |
|------|------|
| `mock-model-server.mjs` | OpenAI 兼容的 mock 模型服务，供本地联调与路由/负载均衡测试使用（见下） |

> 说明：早期一批硬编码了已废弃凭据（`dev-admin-12345` API Key、过期 JWT、旧管理员弱密码）或内网测试机地址（`172.16.30.6`）的 E2E / 版本管理 / headers / 审计 / 追踪数据生成脚本已清理。这些脚本依赖的 `X-API-Key` 认证与旧配置文件 `config-dev/security.api-keys.json` 均已从项目移除；现行追踪接口 `/api/tracing/**` 改由 JWT + 角色矩阵（OPS/ADMIN）鉴权，如需验证请通过控制台登录态调用。

## 常用脚本使用

### 标准构建部署

```bash
# 完整构建部署（推荐用于生产环境）
./scripts/build/build-and-deploy.sh

# 仅构建和部署前端
./scripts/build/build-and-deploy.sh -f

# 验证部署状态
./scripts/build/build-and-deploy.sh -v
```

### Docker 操作

```bash
# 构建镜像
./scripts/build/docker-build.sh

# 国内镜像加速构建
./scripts/build/docker-build-china.sh

# 运行容器
./scripts/build/docker-run.sh [环境] [版本]
```

### 开发调试

```bash
# 本地快速构建并运行（前台，Ctrl+C 停止；默认 31080）
./scripts/dev/run-dev.sh 8080

# 诊断/解除 H2 数据库文件锁（后端启动报 "Database may be already in use" 时）
pwsh scripts/dev/diagnose-db-lock.ps1 -Kill

# 启动隔离的测试实例（默认 8081 端口 + 独立测试库，避开 IDEA 实例的 H2 锁）
pwsh scripts/dev/start-test-instance.ps1 -Reset
```

### 本地 mock 模型服务

Trunkline 的实例健康检查是「socket 探测上游 host:port」，且路由、负载均衡、
熔断、配额等链路都需要一个**真实可达**的上游。当手边没有可用的推理服务
（或只想验证网关自身行为）时，用本 mock 起一个 OpenAI 兼容端点即可跑通完整链路。

零依赖（仅需 Node），支持流式 SSE，并对每条请求打日志（带 `--tag`），
便于观察请求实际落到了哪个实例。

```bash
# 默认监听 127.0.0.1:9099
node scripts/test/mock-model-server.mjs

# 多实例场景用 tag 区分
node scripts/test/mock-model-server.mjs --port 9099 --tag A
node scripts/test/mock-model-server.mjs --port 9100 --tag B

# 模拟慢上游 / 模拟故障（用于验证熔断与降级）
node scripts/test/mock-model-server.mjs --latency 800
node scripts/test/mock-model-server.mjs --fail-rate 0.5
```

支持端点：`GET /v1/models`、`POST /v1/chat/completions`（含 `stream=true` 的 SSE）、
`/v1/embeddings`、`/v1/rerank`、`/v1/audio/speech`、`/v1/audio/transcriptions`、
`/v1/images/generations`、`/v1/images/edits`。其余路径一律返回 200，保证健康检查通过。

在 Trunkline 里把它挂成实例（`Trunkline_Token` 为控制台登录后的 JWT）：

```bash
curl -X POST http://127.0.0.1:8080/api/config/instance/chat \
  -H "Trunkline_Token: <admin-jwt>" -H 'Content-Type: application/json' \
  -d '{"name":"qwen3.8-flash","baseUrl":"http://127.0.0.1:9099",
       "path":"/v1/chat/completions","weight":1,"status":"active","adapter":"gpustack"}'
```

实例健康状态转为 `HEALTHY`（约 20~30s，取决于健康检查周期）后即可发起请求：

```bash
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H "Trunkline_Token: <admin-jwt>" -H 'Content-Type: application/json' \
  -d '{"model":"qwen3.8-flash","messages":[{"role":"user","content":"hi"}],"stream":false}'
```

用完后删除该实例：

```bash
curl -X DELETE http://127.0.0.1:8080/api/config/instance/chat/<instanceDbId> \
  -H "Trunkline_Token: <admin-jwt>"
```

### 监控部署

```bash
# 部署监控栈
./scripts/monitoring/setup-monitoring.sh

# 运行监控测试
./scripts/monitoring/run-monitoring-tests.sh
```

## 注意事项

1. **脚本执行权限**: 确保脚本有执行权限 `chmod +x scripts/**/*.sh`
2. **跨平台**: `.sh` 文件用于 Linux/macOS，`.ps1` 文件用于 Windows PowerShell
3. **强制刷新浏览器**: 部署后务必使用 `Ctrl+Shift+R` 强制刷新

## 静态资源版本问题

如果页面显示旧版本，通常是 Maven 复制前端资源时没清理旧文件，或 Vite 构建触发删除守卫。

**推荐做法**：构建前端前先把旧产物改名，让 Vite 写入全新目录（避开删除守卫导致的中断）：

```bash
cd frontend
[ -d dist ] && mv dist "dist_prev_$(date +%s)"
npm run build
```

然后走标准构建部署：

```bash
./scripts/build/build-and-deploy.sh -f
```

> 兜底：若 `target/classes/static/admin/assets/` 残留旧 hash 文件，手动清理后再构建：
> `rm -rf target/classes/static/admin/assets/* && ./scripts/build/build-and-deploy.sh -f`
