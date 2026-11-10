# Trunkline 监控栈部署指南

本文档介绍如何部署和配置 Trunkline 的完整监控栈，包括 Prometheus、Grafana、AlertManager 等组件。

## 📋 目录结构

```
monitoring/
├── alertmanager/
│   ├── alertmanager.yml          # AlertManager配置
│   └── templates/                # 告警模板目录
├── grafana/
│   ├── dashboards/               # 仪表板JSON文件
│   │   ├── system-overview.json
│   │   ├── business-metrics.json
│   │   ├── infrastructure.json
│   │   ├── performance-analysis.json
│   │   └── alerts-overview.json
│   └── provisioning/             # 自动配置文件
│       ├── datasources/
│       │   └── prometheus.yml    # Prometheus数据源配置
│       ├── dashboards/
│       │   └── dashboards.yml
│       └── plugins/
│           └── plugins.yml       # 插件配置
├── prometheus/
│   ├── prometheus.yml            # Prometheus主配置
│   └── rules/
│       ├── trunkline-alerts.yml   # 告警规则
│       └── trunkline-slow-query-alerts.yml  # 慢查询告警规则
└── data/                         # 数据存储目录
    ├── prometheus/
    ├── grafana/
    └── alertmanager/
```

## 🚀 快速开始

### 方法一：使用一键部署脚本

#### Linux/macOS
```bash
# 给脚本执行权限
chmod +x scripts/setup-monitoring.sh

# 运行部署脚本
./scripts/setup-monitoring.sh
```

#### Windows PowerShell
```powershell
# 运行PowerShell脚本
.\scripts\setup-monitoring.ps1
```

### 方法二：手动部署

1. **创建必要目录**
```bash
mkdir -p monitoring/data/{prometheus,grafana,alertmanager}
```

2. **启动监控栈**
```bash
docker-compose -f docker-compose.monitoring.yml up -d
```

3. **验证服务状态**
```bash
docker-compose -f docker-compose.monitoring.yml ps
```

## 🔧 配置说明

### Prometheus配置

主配置文件：`monitoring/prometheus/prometheus.yml`

关键配置项：
- **抓取间隔**：15秒（全局），Trunkline 为10秒
- **数据保留**：30天或10GB
- **告警规则**：自动加载`rules/`目录下的规则文件

### Grafana配置

#### 数据源自动配置
- 文件：`monitoring/grafana/provisioning/datasources/datasources.yml`
- 自动配置Prometheus数据源
- 默认启用告警查询和Exemplars支持

#### 仪表板自动导入
- 文件：`monitoring/grafana/provisioning/dashboards/dashboards.yml`
- 自动导入所有仪表板JSON文件
- 按功能分组到不同文件夹

#### 默认登录信息
- **用户名**：admin
- **密码**：admin（可通过 `GRAFANA_PASSWORD` 环境变量覆盖）

### AlertManager配置

主配置文件：`monitoring/alertmanager/alertmanager.yml`

告警路由策略：
- **严重告警**：立即通知，5分钟重复
- **警告告警**：30秒等待，30分钟重复
- **Trunkline 特定告警**：15秒等待，15分钟重复

## 📊 监控指标说明

### 系统指标
- **JVM指标**：内存使用、GC统计、线程数
- **HTTP指标**：请求总数、响应时间、状态码分布
- **系统资源**：CPU使用率、内存使用率

### 业务指标
- **模型调用统计**：按服务类型分组（Chat、Embedding等）
- **负载均衡统计**：各策略使用情况和实例分发
- **限流统计**：限流事件和通过率
- **熔断器统计**：状态转换和失败率

### 基础设施指标
- **后端适配器**：按类型分组的调用统计和响应时间
- **健康检查**：各服务实例的健康状态
- **连接池**：连接数和使用率

## 🎯 仪表板说明

### 1. 系统概览 (System Overview)
- 整体请求量和响应时间趋势
- 服务健康状态总览
- JVM内存和GC统计
- 错误率和可用性指标

### 2. 业务指标 (Business Metrics)
- 各AI服务的请求分布
- 模型调用成功率和延迟
- 用户请求模式分析
- 业务峰值和趋势分析

### 3. 基础设施 (Infrastructure)
- 负载均衡器状态和分发统计
- 限流器和熔断器状态
- 后端适配器性能对比
- 连接池和资源使用情况

### 4. 性能分析 (Performance Analysis)
- 响应时间分布热力图
- 吞吐量和并发分析
- 资源使用趋势
- 性能瓶颈识别

### 5. 告警概览 (Alerts Overview)
- 当前活跃告警列表
- 告警历史和趋势
- 告警处理统计
- 服务可用性报告

## 🚨 告警规则说明

### 严重告警 (Critical)
- **服务不可用**：服务停止响应超过1分钟
- **严重错误率**：5xx错误率超过5%
- **严重响应延迟**：95%分位响应时间超过5秒
- **内存严重不足**：JVM堆内存使用率超过90%
- **后端服务不可用**：后端健康检查失败超过1分钟

### 警告告警 (Warning)
- **高错误率**：总错误率超过10%
- **高响应延迟**：95%分位响应时间超过2秒
- **内存使用高**：JVM堆内存使用率超过80%
- **熔断器开启**：熔断器状态变为开启
- **限流频繁触发**：限流拒绝率过高
- **负载不均衡**：实例间负载差异超过50%

## 🔍 故障排查

### 常见问题

#### 1. Grafana无法连接Prometheus
**症状**：仪表板显示"No data"或连接错误

**解决方案**：
```bash
# 检查Prometheus服务状态
docker-compose -f docker-compose.monitoring.yml logs prometheus

# 检查网络连通性
docker exec grafana curl http://prometheus:9090/api/v1/query?query=up
```

#### 2. Trunkline 指标未显示
**症状**：Prometheus targets 页面显示 Trunkline 为 down 状态

**解决方案**：
```bash
# 检查 Trunkline 应用是否启动
curl http://localhost:8080/actuator/health

# 检查指标端点
curl http://localhost:8080/actuator/prometheus

# 检查Docker网络
docker network ls
docker network inspect monitoring_monitoring
```

#### 3. 告警未触发
**症状**：满足告警条件但未收到通知

**解决方案**：
```bash
# 检查告警规则状态
curl http://localhost:9090/api/v1/rules

# 检查AlertManager状态
curl http://localhost:9093/api/v1/status

# 查看AlertManager日志
docker-compose -f docker-compose.monitoring.yml logs alertmanager
```

### 性能优化

#### 1. 减少指标收集开销
```yaml
# 在application.yml中配置采样率
monitoring:
  metrics:
    sampling:
      request-metrics: 0.1      # 10%采样率
      backend-metrics: 0.5      # 50%采样率
```

#### 2. 优化Prometheus存储
```yaml
# 在prometheus.yml中调整保留策略
storage:
  tsdb:
    retention.time: 15d         # 减少到15天
    retention.size: 5GB         # 减少到5GB
```

#### 3. 优化Grafana查询
- 使用合适的时间范围
- 避免过于复杂的查询
- 启用查询缓存

## 📈 扩展配置

### 添加自定义告警规则

1. 在`monitoring/prometheus/rules/`目录下创建新的规则文件
2. 重启Prometheus服务：
```bash
docker-compose -f docker-compose.monitoring.yml restart prometheus
```

### 集成外部通知

#### Slack集成
```yaml
# 在alertmanager.yml中添加Slack配置
receivers:
  - name: 'slack-alerts'
    slack_configs:
      - api_url: 'YOUR_SLACK_WEBHOOK_URL'
        channel: '#alerts'
        title: 'Trunkline Alert'
        text: '{{ range .Alerts }}{{ .Annotations.summary }}{{ end }}'
```

#### 钉钉集成
```yaml
# 在alertmanager.yml中添加钉钉Webhook配置
receivers:
  - name: 'dingtalk-alerts'
    webhook_configs:
      - url: 'YOUR_DINGTALK_WEBHOOK_URL'
        send_resolved: true
```

### 长期存储配置

如需长期存储监控数据，可以配置远程存储：

```yaml
# 在prometheus.yml中添加远程写入配置
remote_write:
  - url: "http://your-remote-storage:9201/write"
    queue_config:
      max_samples_per_send: 1000
      max_shards: 200
```

## 🛠️ 维护操作

### 备份配置
```bash
# 备份配置文件
tar -czf monitoring-config-backup-$(date +%Y%m%d).tar.gz monitoring/

# 备份Grafana数据
docker exec grafana tar -czf /tmp/grafana-backup.tar.gz /var/lib/grafana
docker cp grafana:/tmp/grafana-backup.tar.gz ./grafana-backup-$(date +%Y%m%d).tar.gz
```

### 更新监控栈
```bash
# 拉取最新镜像
docker-compose -f docker-compose.monitoring.yml pull

# 重启服务
docker-compose -f docker-compose.monitoring.yml up -d
```

### 清理旧数据
```bash
# 清理Prometheus旧数据（谨慎操作）
docker exec prometheus rm -rf /prometheus/01*

# 清理Docker未使用的卷
docker volume prune
```

## 📞 支持与反馈

如果在部署或使用过程中遇到问题，请：

1. 查看相关服务的日志
2. 检查配置文件语法
3. 验证网络连通性
4. 参考本文档的故障排查部分

更多技术支持，请联系开发团队或提交Issue。