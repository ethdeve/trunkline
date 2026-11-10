# Trunkline 监控栈一键部署脚本 (PowerShell版本)
# 该脚本用于快速部署完整的监控环境，包括Prometheus、Grafana、AlertManager等组件

param(
    [switch]$Help,
    [switch]$Clean,
    [switch]$Status
)

# 错误处理
$ErrorActionPreference = "Stop"

# 颜色定义
function Write-ColorOutput {
    param(
        [string]$Message,
        [string]$Color = "White"
    )
    
    switch ($Color) {
        "Red" { Write-Host $Message -ForegroundColor Red }
        "Green" { Write-Host $Message -ForegroundColor Green }
        "Yellow" { Write-Host $Message -ForegroundColor Yellow }
        "Blue" { Write-Host $Message -ForegroundColor Blue }
        default { Write-Host $Message }
    }
}

# 日志函数
function Log-Info {
    param([string]$Message)
    Write-ColorOutput "[INFO] $Message" "Blue"
}

function Log-Success {
    param([string]$Message)
    Write-ColorOutput "[SUCCESS] $Message" "Green"
}

function Log-Warning {
    param([string]$Message)
    Write-ColorOutput "[WARNING] $Message" "Yellow"
}

function Log-Error {
    param([string]$Message)
    Write-ColorOutput "[ERROR] $Message" "Red"
}

# 检查命令是否存在
function Test-Command {
    param([string]$Command)
    
    try {
        Get-Command $Command -ErrorAction Stop | Out-Null
        return $true
    }
    catch {
        return $false
    }
}

# 检查Docker和Docker Compose
function Test-Prerequisites {
    Log-Info "检查系统依赖..."
    
    if (-not (Test-Command "docker")) {
        Log-Error "Docker命令未找到，请先安装Docker Desktop"
        exit 1
    }
    
    if (-not (Test-Command "docker-compose")) {
        Log-Error "docker-compose命令未找到，请确保Docker Compose已安装"
        exit 1
    }
    
    # 检查Docker是否运行
    try {
        docker info | Out-Null
    }
    catch {
        Log-Error "Docker服务未运行，请启动Docker Desktop"
        exit 1
    }
    
    Log-Success "系统依赖检查通过"
}

# 创建必要的目录结构
function New-MonitoringDirectories {
    Log-Info "创建监控目录结构..."
    
    # 创建数据目录
    $directories = @(
        "monitoring\data\prometheus",
        "monitoring\data\grafana",
        "monitoring\data\alertmanager",
        "monitoring\prometheus\rules",
        "monitoring\grafana\dashboards",
        "monitoring\grafana\provisioning\datasources",
        "monitoring\grafana\provisioning\dashboards",
        "monitoring\grafana\provisioning\plugins",
        "monitoring\alertmanager\templates",
        "logs"
    )
    
    foreach ($dir in $directories) {
        if (-not (Test-Path $dir)) {
            New-Item -ItemType Directory -Path $dir -Force | Out-Null
        }
    }
    
    Log-Success "目录结构创建完成"
}

# 验证配置文件
function Test-ConfigFiles {
    Log-Info "验证配置文件..."
    
    # 检查必要的配置文件是否存在
    $requiredFiles = @(
        "docker-compose.monitoring.yml",
        "monitoring\prometheus\prometheus.yml",
        "monitoring\grafana\provisioning\datasources\datasources.yml",
        "monitoring\grafana\provisioning\dashboards\dashboards.yml",
        "monitoring\alertmanager\alertmanager.yml"
    )
    
    foreach ($file in $requiredFiles) {
        if (-not (Test-Path $file)) {
            Log-Error "配置文件 $file 不存在"
            exit 1
        }
    }
    
    # 验证Docker Compose配置
    try {
        docker-compose -f docker-compose.monitoring.yml config | Out-Null
    }
    catch {
        Log-Error "Docker Compose配置文件验证失败"
        exit 1
    }
    
    Log-Success "配置文件验证通过"
}

# 拉取Docker镜像
function Get-DockerImages {
    Log-Info "拉取Docker镜像..."
    
    try {
        docker-compose -f docker-compose.monitoring.yml pull
    }
    catch {
        Log-Error "Docker镜像拉取失败: $_"
        exit 1
    }
    
    Log-Success "Docker镜像拉取完成"
}

# 启动监控服务
function Start-MonitoringServices {
    Log-Info "启动监控服务..."
    
    # 停止可能存在的旧服务
    try {
        docker-compose -f docker-compose.monitoring.yml down 2>$null
    }
    catch {
        # 忽略错误，可能是首次运行
    }
    
    # 启动服务
    try {
        docker-compose -f docker-compose.monitoring.yml up -d
    }
    catch {
        Log-Error "监控服务启动失败: $_"
        exit 1
    }
    
    Log-Success "监控服务启动完成"
}

# 等待服务就绪
function Wait-ForServices {
    Log-Info "等待服务启动..."
    
    $services = @(
        @{Name="Prometheus"; Port=9090},
        @{Name="Grafana"; Port=3000},
        @{Name="AlertManager"; Port=9093}
    )
    
    $maxAttempts = 30
    
    foreach ($service in $services) {
        Log-Info "等待 $($service.Name) 服务启动..."
        $attempt = 0
        
        do {
            try {
                $response = Invoke-WebRequest -Uri "http://localhost:$($service.Port)" -TimeoutSec 2 -ErrorAction Stop
                break
            }
            catch {
                if ($attempt -ge $maxAttempts) {
                    Log-Error "$($service.Name) 服务启动超时"
                    return $false
                }
                
                Start-Sleep -Seconds 2
                $attempt++
                Write-Host "." -NoNewline
            }
        } while ($attempt -lt $maxAttempts)
        
        Write-Host ""
        Log-Success "$($service.Name) 服务已就绪"
    }
    
    return $true
}

# 验证服务状态
function Test-ServiceStatus {
    Log-Info "验证服务状态..."
    
    # 检查容器状态
    $containers = @("prometheus", "grafana", "alertmanager")
    
    foreach ($container in $containers) {
        $running = docker ps --format "table {{.Names}}" | Select-String $container
        if (-not $running) {
            Log-Error "$container 容器未运行"
            return $false
        }
    }
    
    # 检查服务健康状态
    $healthChecks = @(
        @{Url="http://localhost:9090/-/healthy"; Name="Prometheus"},
        @{Url="http://localhost:3000/api/health"; Name="Grafana"},
        @{Url="http://localhost:9093/-/healthy"; Name="AlertManager"}
    )
    
    foreach ($check in $healthChecks) {
        try {
            Invoke-WebRequest -Uri $check.Url -TimeoutSec 5 | Out-Null
            Log-Success "$($check.Name) 健康检查通过"
        }
        catch {
            Log-Warning "$($check.Name) 健康检查失败，但服务可能仍在启动中"
        }
    }
    
    return $true
}

# 显示访问信息
function Show-AccessInfo {
    Log-Success "Trunkline 监控栈部署完成！"
    Write-Host ""
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host "服务访问信息：" -ForegroundColor Cyan
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host "🎯 Grafana仪表板:     http://localhost:3000" -ForegroundColor White
    Write-Host "   用户名: admin" -ForegroundColor Gray
    Write-Host "   密码: admin" -ForegroundColor Gray
    Write-Host ""
    Write-Host "📊 Prometheus:        http://localhost:9090" -ForegroundColor White
    Write-Host "🚨 AlertManager:      http://localhost:9093" -ForegroundColor White
    Write-Host "📈 Trunkline 指标:     http://localhost:8080/actuator/prometheus" -ForegroundColor White
    Write-Host ""
    Write-Host "🖥️  系统监控:" -ForegroundColor White
    Write-Host "   Node Exporter:     http://localhost:9100/metrics" -ForegroundColor Gray
    Write-Host "   cAdvisor:          http://localhost:8081" -ForegroundColor Gray
    Write-Host ""
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host "常用命令：" -ForegroundColor Cyan
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host "查看服务状态:   docker-compose -f docker-compose.monitoring.yml ps" -ForegroundColor White
    Write-Host "查看服务日志:   docker-compose -f docker-compose.monitoring.yml logs -f [service]" -ForegroundColor White
    Write-Host "停止监控栈:     docker-compose -f docker-compose.monitoring.yml down" -ForegroundColor White
    Write-Host "重启监控栈:     docker-compose -f docker-compose.monitoring.yml restart" -ForegroundColor White
    Write-Host ""
    Write-Host "🔧 配置文件位置：" -ForegroundColor White
    Write-Host "   Prometheus:        monitoring\prometheus\prometheus.yml" -ForegroundColor Gray
    Write-Host "   Grafana:           monitoring\grafana\provisioning\" -ForegroundColor Gray
    Write-Host "   AlertManager:      monitoring\alertmanager\alertmanager.yml" -ForegroundColor Gray
    Write-Host "   告警规则:          monitoring\prometheus\rules\trunkline-alerts.yml" -ForegroundColor Gray
    Write-Host ""
    Write-Host "📚 文档和工具：" -ForegroundColor White
    Write-Host "   告警规则指南:      monitoring\prometheus\ALERT_RULES_GUIDE.md" -ForegroundColor Gray
    Write-Host "   测试告警规则:      .\monitoring\prometheus\test-alerts.ps1" -ForegroundColor Gray
    Write-Host "   验证告警规则:      .\monitoring\prometheus\validate-alerts.ps1" -ForegroundColor Gray
    Write-Host ""
}

# 清理函数
function Remove-MonitoringStack {
    Log-Info "清理现有监控栈..."
    
    try {
        docker-compose -f docker-compose.monitoring.yml down -v
        docker system prune -f
        Log-Success "清理完成"
    }
    catch {
        Log-Error "清理过程中出现错误: $_"
        exit 1
    }
}

# 显示服务状态
function Show-ServiceStatus {
    Log-Info "检查监控栈状态..."
    docker-compose -f docker-compose.monitoring.yml ps
}

# 显示帮助信息
function Show-Help {
    Write-Host "Trunkline 监控栈部署脚本 (PowerShell版本)" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "用法: .\setup-monitoring.ps1 [选项]" -ForegroundColor White
    Write-Host ""
    Write-Host "选项:" -ForegroundColor White
    Write-Host "  -Help          显示帮助信息" -ForegroundColor Gray
    Write-Host "  -Clean         清理现有部署" -ForegroundColor Gray
    Write-Host "  -Status        显示服务状态" -ForegroundColor Gray
    Write-Host ""
    Write-Host "示例:" -ForegroundColor White
    Write-Host "  .\setup-monitoring.ps1           # 部署监控栈" -ForegroundColor Gray
    Write-Host "  .\setup-monitoring.ps1 -Clean    # 清理部署" -ForegroundColor Gray
    Write-Host "  .\setup-monitoring.ps1 -Status   # 查看状态" -ForegroundColor Gray
    Write-Host ""
}

# 主函数
function Main {
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host "🚀 Trunkline 监控栈部署脚本 (PowerShell)" -ForegroundColor Cyan
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host ""
    
    try {
        # 执行部署步骤
        Test-Prerequisites
        New-MonitoringDirectories
        Test-ConfigFiles
        Get-DockerImages
        Start-MonitoringServices
        
        if (Wait-ForServices) {
            Test-ServiceStatus | Out-Null
            
            # 验证告警规则
            Log-Info "验证告警规则配置..."
            try {
                $null = Get-Command promtool -ErrorAction Stop
                & "$PSScriptRoot\..\monitoring\prometheus\test-alerts.ps1" -PrometheusUrl "http://localhost:9090"
            } catch {
                Log-Warning "promtool未安装，跳过告警规则验证"
            }
            
            Show-AccessInfo
            Log-Success "监控栈部署成功完成！"
        }
        else {
            Log-Error "服务启动验证失败"
            exit 1
        }
    }
    catch {
        Log-Error "部署过程中出现错误: $_"
        Log-Info "正在清理..."
        try {
            docker-compose -f docker-compose.monitoring.yml down 2>$null
        }
        catch {
            # 忽略清理错误
        }
        exit 1
    }
}

# 处理命令行参数
if ($Help) {
    Show-Help
    exit 0
}
elseif ($Clean) {
    Remove-MonitoringStack
    exit 0
}
elseif ($Status) {
    Show-ServiceStatus
    exit 0
}
else {
    Main
}