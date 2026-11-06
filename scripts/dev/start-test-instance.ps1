<#
.SYNOPSIS
    启动一个与 IDEA 实例完全隔离的后端实例（用于端到端测试 / 临时验证）。

.DESCRIPTION
    为什么要这个脚本：
      本项目 H2 是「嵌入式文件库」，同一时刻只允许一个 JVM 打开
      ./data/trunkline.mv.db。如果测试实例和 IDEA 实例共用同一个数据文件，
      后启动的那个必然报：
          Database may be already in use: ".../data/trunkline.mv.db"
          The file is locked: .../data/trunkline.mv.db

    本脚本通过两点彻底隔离：
      1. 端口：默认 8081，不与 IDEA 的 8080 冲突
      2. 数据库：默认 ./data/trunkline-test.mv.db，独立数据文件、独立锁

    数据文件可以用 -DataFile 改；-Reset 会先删掉旧测试库（重新初始化）。

.PARAMETER Port
    监听端口，默认 8081。

.PARAMETER DataFile
    测试用 H2 数据文件（不含扩展名），默认 ./data/trunkline-test。

.PARAMETER Reset
    启动前删除旧的测试数据文件（重新建库）。

.PARAMETER Foreground
    前台运行（Ctrl+C 结束）；默认后台运行并返回 PID。

.EXAMPLE
    pwsh scripts/dev/start-test-instance.ps1 -Reset

.EXAMPLE
    pwsh scripts/dev/start-test-instance.ps1 -Port 8082 -DataFile ./data/trunkline-test2
#>
[CmdletBinding()]
param(
    [int]$Port = 8081,
    [string]$DataFile = './data/trunkline-test',
    [switch]$Reset,
    [switch]$Foreground
)

$ErrorActionPreference = 'Stop'

function Write-Info([string]$msg) { Write-Host "[信息] $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "[正常] $msg" -ForegroundColor Green }
function Write-Err([string]$msg)  { Write-Host "[错误] $msg" -ForegroundColor Red }

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $projectRoot

# ---------- 1. 校验目标库未被占用 ----------
$dbPath = if ([System.IO.Path]::IsPathRooted($DataFile)) { $DataFile } else { Join-Path $projectRoot $DataFile }
$dbFile = "$dbPath.mv.db"
$lockFile = "$dbPath.lock.db"

if (Test-Path $lockFile) {
    Write-Err "测试库仍被占用：$lockFile"
    Write-Host "  请先结束占用它的进程，或用 -Reset 重建测试库。"
    exit 1
}

if ($Reset -and (Test-Path $dbFile)) {
    try {
        Remove-Item $dbFile -Force -ErrorAction Stop
        Write-Ok "已删除旧测试库 $dbFile"
    } catch {
        Write-Err "删除旧测试库失败：$($_.Exception.Message)"
        exit 1
    }
}

# ---------- 2. 组装 classpath ----------
# 优先用 target/classes + ~/.m2 依赖；tools/backend_cp.txt 若存在则直接复用
$cpFile = 'C:\Users\Administrator\WorkBuddy\2026-10-15-10-45-00\tools\backend_cp.txt'
if (-not (Test-Path $cpFile)) {
    $cpFile = Join-Path $projectRoot 'scripts\dev\backend_cp.txt'
}
if (-not (Test-Path $cpFile)) {
    Write-Err "找不到 classpath 文件（backend_cp.txt）。请先运行 extract_cp.py 从 IDEA 进程提取。"
    exit 1
}
$cp = (Get-Content $cpFile -Raw) -replace "`r`n", ''

# ---------- 3. 启动 ----------
$java = 'C:\Program Files\Java\jdk-17.0.13\bin\java.exe'
if (-not (Test-Path $java)) {
    $java = (Get-Command java -ErrorAction SilentlyContinue).Source
}
if (-not $java) { Write-Err "找不到 java 可执行文件"; exit 1 }

$logFile = Join-Path $env:TEMP "ja-test-instance-$Port.log"
$jdbcUrl = "jdbc:h2:file:$($DataFile -replace '\\','/');DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=FALSE"

$javaArgs = @(
    '-Dfile.encoding=UTF-8',
    '-Dspring.profiles.active=dev',
    "-Dspring.datasource.url=$jdbcUrl",
    '-cp', $cp,
    'org.unreal.modelrouter.ModelRouterApplication',
    "--server.port=$Port"
)

Write-Info "端口：$Port"
Write-Info "测试库：$dbFile"
Write-Info "日志：$logFile"

if ($Foreground) {
    Write-Info "前台启动（Ctrl+C 结束）"
    # 注意：PowerShell 会把 -Dfile.encoding=UTF-8 里的 '=' 前部分当成参数名解析
    # （实测报「找不到或无法加载主类 .encoding=UTF-8」），必须整体加引号。
    $fgArgs = @()
    foreach ($a in $javaArgs) {
        if ($a -match '[\s;]') { $fgArgs += '"' + $a + '"' } else { $fgArgs += $a }
    }
    & $java @fgArgs
    exit $LASTEXITCODE
}

# ---- 后台启动：用 .cmd 包装 + 文件重定向 ----
# 不用 ProcessStartInfo.RedirectStandardOutput：父 PowerShell 退出后管道断开，
# JVM 写 stdout 会立刻抛 IOException 而秒退。
# 也不用 Start-Process：本机环境有 4 个大小写不同的 proxy 变量
# （HTTP_PROXY/http_proxy/HTTPS_PROXY/https_proxy），Start-Process 构造环境变量字典时
# 报「已添加项。字典中的关键字:"http_proxy"所添加的关键字:"HTTP_PROXY"」而失败。
$cmdFile = Join-Path $env:TEMP "ja-test-instance-$Port.cmd"

$javaArgsQuoted = @()
foreach ($a in $javaArgs) {
    # classpath 含空格与分号，必须整体加引号
    if ($a -match '[\s;]') { $javaArgsQuoted += '"' + $a + '"' } else { $javaArgsQuoted += $a }
}
$cmdBody = @(
    '@echo off',
    "cd /d `"$projectRoot`"",
    "`"$java`" $($javaArgsQuoted -join ' ') > `"$logFile`" 2>&1",
    'exit /b %errorlevel%'
) -join "`r`n"

[System.IO.File]::WriteAllText($cmdFile, $cmdBody, [System.Text.Encoding]::Default)

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $cmdFile
$psi.UseShellExecute = $true     # 走 ShellExecute，绕开环境变量字典构造
$psi.WindowStyle = 'Hidden'
$proc = [System.Diagnostics.Process]::Start($psi)

Write-Ok "已启动，PID=$($proc.Id)"
Write-Host ""
Write-Info "常用操作："
Write-Host "  查看日志  : Get-Content '$logFile' -Tail 50"
Write-Host "  停止实例  : Stop-Process -Id $($proc.Id) -Force"
Write-Host "  健康检查  : Invoke-WebRequest http://localhost:$Port/actuator/health -UseBasicParsing"
Write-Host ""
Write-Info "注意：该实例使用独立数据库，与 IDEA 实例的数据互不影响；"
Write-Host "       测试数据不会出现在 IDEA 的控制台里，需要时自行造数。"
