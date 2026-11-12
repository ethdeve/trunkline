# Trunkline 开发者文档

> 面向**接手项目、二次开发、参与贡献**的开发者。基于 **v0.1.0** 真实代码编写，路径均相对仓库根目录。
>
> 如果你是**使用者**（想知道怎么装、怎么配、怎么用功能），请看：
> - [README-ZH.md](../../README-ZH.md) / [README.md](../../README.md) —— 产品简介与安装（普通用户走安装包，技术人员走 Docker/jar）
> - 控制台内的「帮助中心」—— 面向终端用户的操作指南（中英各 19 篇，随应用内置）

## 文档索引

| 文档 | 讲什么 | 适合什么时候读 |
|---|---|---|
| [架构速览](architecture.md) | 技术栈、11 个模块职责、一次模型调用的完整链路、韧性四件套、配额与计量 | 刚接手项目、想建立全局认知 |
| [扩展指南](extending.md) | 新增供应商模板/负载均衡/降级/端点/配置项的操作，以及必须同步的双写点与测试 | 要改功能、加厂商、加策略 |
| [数据模型](data-model.md) | 21 张表的分组、关系、关键字段，H2/PostgreSQL 双脚本与迁移约定 | 改实体、查数据、写报表 |
| [配置参考](configuration.md) | 23 个 yml 的分组、import 优先级、profile 差异、环境变量、易踩项 | 调配置、排查"改了没生效" |

## 上手路径（先看这两份）

- **环境搭建、常用命令、提交规范** —— 见仓库根的 [CONTRIBUTING.md](../../CONTRIBUTING.md)。本文不重复。
- **安全漏洞报告** —— 见 [SECURITY.md](../../SECURITY.md)，请勿公开提交。

最小启动（详见 CONTRIBUTING）：

```bash
./mvnw test -Dfrontend.build.skip=true          # 只跑后端测试，最快
./mvnw clean package                            # 完整打包（含前端控制台）
java -jar target/trunkline-0.1.0.jar            # 首次启动访问 /init 完成向导
```

## 阅读约定

- 文中类名均给出相对 `src/main/java/org/unreal/modelrouter/` 的子路径；测试类相对 `src/test/java/`。
- 涉及"**已发布基线不可改**""**router 恰好 3 个配置**""**模板数量 13 被断言锁定**"等，都是代码里真实存在的守卫测试约束，改动前务必读对应章节。
- 本文只描述**现状**；规划中的能力见 README 的产品蓝图。

---

Trunkline Team · Apache-2.0 · 基于 JAiRouter
