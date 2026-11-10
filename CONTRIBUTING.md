# 参与贡献 / Contributing

欢迎提问题、改文档、修缺陷、加功能。这份说明讲清从哪里入手、怎么本地跑起来、提交前要检查什么。

## 先找对地方

| 你想做的事 | 去哪里 |
| --- | --- |
| 报告缺陷：崩溃、结果不对、文档写错 | Issues（先搜一下有没有重复） |
| 提想法、问用法、讨论方向 | Discussions |
| 报告安全漏洞 | 按 [SECURITY.md](SECURITY.md) 私有报告，**不要公开** |

## 开发环境

- **Java 17 或以上**（项目的编译目标是 17）
- **Node.js 20+** 与 npm（后端构建时会自动构建前端）
- Maven 用仓库自带的 `./mvnw`，不需要另装系统 Maven
- 数据库默认用内置 H2，数据落在 `./data`；要切 PostgreSQL 见 `application-dev.yml` 里的 `DATABASE_URL` 配置

## 常用命令

```bash
# 后端：只跑测试（跳过前端构建，最快）
./mvnw test -Dfrontend.build.skip=true

# 后端：完整打包（含前端构建，产出可运行的 jar）
./mvnw clean package

# 只想快速验证后端能打包
./mvnw clean package -DskipTests -Dfrontend.build.skip=true

# 前端：类型检查 / 开发服务器 / 构建
cd frontend && npm run type-check
cd frontend && npm run dev
cd frontend && npm run build

# 启动
java -jar target/trunkline-0.1.0.jar
```

首次启动会停在初始化向导：开发模式打开 <http://localhost:8080/init>，jar 部署则为 `/admin/init`，按提示创建管理员。

## 代码约定

- 新增 Java 文件保留统一版权头：`Copyright (c) 2026 Trunkline Team. All Rights Reserved.` 与 `@author Trunkline Team`。
- 继承上游 JAiRouter 的文件保持原有署名不变，详见 [NOTICE](NOTICE)。
- **供应商模板**改动要同时更新后端 `AdapterTemplateService.initTemplates()`、前端 `frontend/src/constants/providerPresets.ts`，以及相关数量断言测试；两处集合必须一致。
- **权限**是两道闸门：URL 层的固定角色矩阵和方法上的 `@PreAuthorize` 必须同口径。同步返回 `ResponseEntity` 的控制器不能加 `@PreAuthorize`（响应式方法安全只对 `Mono`/`Flux` 生效）。新增端点要么登记访问级别，要么明确列入白名单，否则默认只有管理员能访问。
- **配置文件**目录结构有守卫测试（`ConfigFileStructureTest`）锁定，增删文件请同步 `application.yml` 的 import 与测试。
- **数据库迁移**：已发布的 Flyway 脚本不可修改；实体有改动要同时补 H2 与 PostgreSQL 两侧脚本。

## 提交与 Pull Request

- 一个提交只做一件事，提交信息说清「为什么改」，不只是「改了什么」。
- PR 描述里关联相关 issue，并写明你怎么验证的（跑过哪些测试、在哪个环境试过）。
- 提交前至少跑通 `./mvnw test` 和 `cd frontend && npm run type-check`。
- 请不要把 `.env`、`data/` 下的数据库、密钥文件或调试截图提交进来；`.gitignore` 已经覆盖这些，但提交前还是看一眼 `git status`。

## 许可

本项目遵循 [Apache 2.0](LICENSE)。贡献即表示你同意自己的改动以同一协议发布，并保留 JAiRouter 上游的署名与版权声明。
