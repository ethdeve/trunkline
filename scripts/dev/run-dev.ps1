#!/usr/bin/env pwsh

# 快速构建并运行 Trunkline 应用
# 适用于 Windows PowerShell 环境

param(
    [int]$Port = 31080,
    [string]$Profile = "fast"
)

Write-Host "🚀 开始构建 Trunkline 应用..." -ForegroundColor Green

# 检查 Maven 是否安装
try {
    $mvnVersion = mvn -v
    Write-Host "✅ Maven 已安装" -ForegroundColor Green
} catch {
    Write-Host "❌ 未找到 Maven，请先安装 Maven" -ForegroundColor Red
    exit 1
}

# 执行 Maven 构建
Write-Host "🔨 执行 mvn package -P$Profile ..." -ForegroundColor Yellow
mvn package -P$Profile

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ 构建失败" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "✅ 构建成功完成" -ForegroundColor Green

# 查找构建好的 JAR 文件
$jarFile = Get-ChildItem -Path "target" -Filter "trunkline-*.jar" | Select-Object -First 1

if (-not $jarFile) {
    Write-Host "❌ 未找到构建好的 JAR 文件" -ForegroundColor Red
    exit 1
}

Write-Host "📦 找到 JAR 文件: $($jarFile.Name)" -ForegroundColor Green

# 运行应用
Write-Host "🏃 运行应用，端口: $Port" -ForegroundColor Yellow
Write-Host "🔗 访问地址: http://localhost:$Port" -ForegroundColor Cyan
Write-Host "⏹️  按 Ctrl+C 停止应用" -ForegroundColor Cyan

java -jar -Dserver.port=$Port "./target/$($jarFile.Name)"