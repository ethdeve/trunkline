<#
.SYNOPSIS
    诊断并修复 H2 文件库被占用导致的后端启动失败。

.DESCRIPTION
    典型报错：
        Database may be already in use: ".../data/trunkline.mv.db"
        The file is locked: .../data/trunkline.mv.db [2.3.232/7]

    根因：H2 嵌入式文件库同一时刻只允许一个 JVM 持有文件锁。
    当上一次的后端进程没被彻底结束（IDEA 里点了 Stop 但进程仍在、
    或从命令行手动起过一个实例忘了关），IDEA 再启动就会在
    Flyway 阶段直接失败。

    本脚本做三件事：
      1. 找出占用该 H2 文件的 java 进程（按 classpath 含项目路径 + 启动主类匹配）
      2. 报告 8080 端口占用情况
      3. 加 -Kill 开关时结束占用进程并等待锁文件释放

.EXAMPLE
    # 只看诊断信息，不动任何进程
    pwsh scripts/dev/diagnose-db-lock.ps1

.EXAMPLE
    # 诊断并结束占锁的残留后端进程
    pwsh scripts/dev/diagnose-db-lock.ps1 -Kill

.NOTES
    只匹配本项目的后端进程（classpath 含 trunkline 或主类为
    ModelRouterApplication），不会误杀 IDEA 自身的 JPS 编译进程
    （它的主类是 org.jetbrains.jps.cmdline.Launcher）。
#>
[CmdletBinding()]
param(
    [switch]$Kill,

    # 项目根目录；留空时自动取脚本上级两级（scripts/dev -> 项目根）
    [string]$ProjectRoot = ''
)

$ErrorActionPreference = 'Continue'

# 注意：$PSScriptRoot 在 param 默认值求值阶段尚不可用，必须在脚本体内推导
if (-not $ProjectRoot) {
    $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}

function Write-Info([string]$msg) { Write-Host "[信息] $msg" -ForegroundColor Cyan }
function Write-Warn([string]$msg) { Write-Host "[警告] $msg" -ForegroundColor Yellow }
function Write-Ok([string]$msg)   { Write-Host "[正常] $msg" -ForegroundColor Green }
function Write-Err([string]$msg)  { Write-Host "[错误] $msg" -ForegroundColor Red }

# ---------- 1. 定位 H2 数据文件 ----------
$dataDir = Join-Path $ProjectRoot 'data'
if (-not (Test-Path $dataDir)) {
    Write-Info "未找到数据目录 $dataDir，跳过文件检查"
} else {
    $dbFiles = Get-ChildItem -Path $dataDir -Filter '*.mv.db' -ErrorAction SilentlyContinue
    $lockFiles = Get-ChildItem -Path $dataDir -Filter '*.lock.db' -ErrorAction SilentlyContinue

    foreach ($f in $dbFiles) {
        Write-Info "H2 数据文件: $($f.FullName) ($([math]::Round($f.Length/1KB,1)) KB, 修改于 $($f.LastWriteTime))"
    }
    if ($lockFiles) {
        foreach ($f in $lockFiles) {
            Write-Warn "存在锁文件: $($f.Name)  —— 通常意味着有进程正在使用数据库"
        }
    } else {
        Write-Ok "无 .lock.db 文件，数据库当前未被占用"
    }
}

# ---------- 2. 找占用进程 ----------
# 只匹配本项目后端：命令行含项目路径 或 主类为 ModelRouterApplication
# 排除 IDEA 自身进程（JPS 编译服务主类是 org.jetbrains.jps.cmdline.Launcher）
$backendProcs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
    $cl = $_.CommandLine
    if (-not $cl) { return $false }
    $isJps = $cl -like '*org.jetbrains.jps.cmdline.Launcher*'
    if ($isJps) { return $false }
    return ($cl -like '*ModelRouterApplication*') -or ($cl -like "*$ProjectRoot*")
}

if ($backendProcs) {
    foreach ($p in $backendProcs) {
        $port = ''
        $conn = Get-NetTCPConnection -OwningProcess $p.ProcessId -State Listen -ErrorAction SilentlyContinue |
                Where-Object { $_.LocalPort -in 8080, 8081, 8082, 8443 }
        if ($conn) { $port = " (监听端口: $($conn.LocalPort -join ', '))" }
        Write-Warn "PID $($p.ProcessId) 启动于 $($p.CreationDate)$port"
    }
} else {
    Write-Ok "未发现残留的后端 java 进程"
}

# ---------- 3. 端口占用 ----------
foreach ($pt in 8080) {
    $c = Get-NetTCPConnection -LocalPort $pt -State Listen -ErrorAction SilentlyContinue
    if ($c) {
        Write-Warn "端口 $pt 被占用 (PID $($c.OwningProcess -join ', '))"
    } else {
        Write-Ok "端口 $pt 空闲"
    }
}

# ---------- 4. 按需结束进程 ----------
if ($Kill -and $backendProcs) {
    foreach ($p in $backendProcs) {
        try {
            Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop
            Write-Ok "已结束 PID $($p.ProcessId)"
        } catch {
            Write-Err "结束 PID $($p.ProcessId) 失败: $($_.Exception.Message)"
        }
    }
    # 等 H2 释放文件锁（进程退出后 MVStore 需完成 close）
    Start-Sleep -Seconds 3
    if (Test-Path $dataDir) {
        $still = Get-ChildItem -Path $dataDir -Filter '*.lock.db' -ErrorAction SilentlyContinue
        if ($still) {
            Write-Warn "锁文件仍存在，若仍启动失败请检查是否有 IDE 内的运行实例未停止"
        } else {
            Write-Ok "锁文件已释放，可以重新在 IDEA 中启动"
        }
    }
} elseif (-not $Kill -and $backendProcs) {
    Write-Info "如需结束上述占锁进程，请加 -Kill 参数重跑"
}

Write-Host ''
Write-Info "排查要点："
Write-Host '  1. IDEA 里点 Stop 后进程可能仍在（Run 窗口的红色方块要确认真的停了）；'
Write-Host '  2. 命令行手动起过后端实例时，用完必须 Ctrl+C 或 Stop-Process 结束；'
Write-Host '  3. 本项目 H2 为嵌入式文件库，同一时刻只允许一个 JVM 打开；'
Write-Host '  4. 若需同时跑两个实例，可给其中一个指定不同的 DATABASE_URL（不同数据文件）。'
