<#
.SYNOPSIS
    Trunkline 桌面便携版打包脚本 — Windows（app-image + zip，内置 JRE，双击 exe 即用）

.DESCRIPTION
    固化本机已实测通过的 jpackage app-image 流程，含以下踩坑处理：
      1. jpackage 自动裁剪 runtime 会漏 java.naming（Logback JNDI）→ 先用宽模块集 jlink 出完整 runtime。
      2. fat jar 入口是 JarLauncher，--main-jar 时不要显式指定业务主类。
      3. mvn clean 会扫描整个 target/ 删文件，exe 常被 Windows 顽固锁定 →
         打包前 taskkill，并把 dist-installer* 备份移出 target/ 到项目根 .build-stash/。
      4. 宽模块 runtime(110M) 暂存到 .packager-runtime-bak（项目根，已 gitignore）避 clean，package 后移回。
      5. 会话注入的 SERVER__PORT/SERVER__HOST 会劫持端口 → 注入 -Dserver.port=8080 压过。
      6. 自动开浏览器由 Java 层 DesktopBrowserLauncher 负责（桌面 API + OS 命令双兜底）。
      7. 测试产生 data/logs 必须移出交付目录，不能发给用户。

.PARAMETER SkipBuild
    复用现有 target/trunkline-0.1.0.jar，不重新 clean package（调试打包流程用）。

.PARAMETER JavaHome
    JDK 目录（需含 bin\jlink.exe 与 bin\jpackage.exe）。默认自动探测 GraalVM 21 / JAVA_HOME。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\build\package-desktop-windows.ps1
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\build\package-desktop-windows.ps1 -SkipBuild
#>
param(
    [switch]$SkipBuild,
    [string]$JavaHome = ""
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Info($msg) { Write-Host "[INFO] $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "[OK]   $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "[WARN] $msg" -ForegroundColor Yellow }
function Err($msg)  { Write-Host "[ERROR] $msg" -ForegroundColor Red }

$PROJECT_ROOT = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
# 版本号从 pom.xml 顶层 <version> 读取，避免打包脚本与 Maven 坐标脱钩。
[xml]$POM = Get-Content (Join-Path $PROJECT_ROOT "pom.xml") -Raw
$APP_VERSION = ([string]$POM.project.version).Trim()
if ([string]::IsNullOrEmpty($APP_VERSION)) { throw "无法从 pom.xml 解析顶层 <version>" }
$JAR_NAME     = "trunkline-$APP_VERSION.jar"
$MODULES      = "java.se,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.management,jdk.management.agent,jdk.attach,java.instrument,jdk.localedata,jdk.jcmd"

Set-Location $PROJECT_ROOT

# ---------- 定位 JDK（jlink + jpackage） ----------
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $candidates = @(
        "C:\Program Files\Java\graalvm-jdk-21",
        $env:JAVA_HOME
    ) | Where-Object { $_ -and (Test-Path (Join-Path $_ "bin\jpackage.exe")) }
    if ($candidates.Count -eq 0) {
        Err "未找到含 jpackage.exe 的 JDK 21。请用 -JavaHome 指定，或安装 GraalVM 21 / 设置 JAVA_HOME。"
        exit 1
    }
    $JavaHome = $candidates[0]
}
$JLINK    = Join-Path $JavaHome "bin\jlink.exe"
$JPACKAGE = Join-Path $JavaHome "bin\jpackage.exe"
if (-not (Test-Path $JLINK))    { Err "缺少 $JLINK"; exit 1 }
if (-not (Test-Path $JPACKAGE)) { Err "缺少 $JPACKAGE"; exit 1 }
Info "JDK: $JavaHome"

$TARGET       = Join-Path $PROJECT_ROOT "target"
$RUNTIME      = Join-Path $TARGET "installer-runtime"
$RT_STASH     = Join-Path $PROJECT_ROOT ".packager-runtime-bak"   # 项目根，已 gitignore，避 mvn clean
$APP_INPUT    = Join-Path $TARGET "app-input"
$DEST         = Join-Path $TARGET "dist-installer"
$APP_DIR      = Join-Path $DEST "Trunkline"
$STASH_OUT    = Join-Path $PROJECT_ROOT ".build-stash"            # 移出 target/ 的被锁备份，已 gitignore

# ---------- 结束占用 exe 的残留进程 ----------
$procs = Get-Process -Name Trunkline -ErrorAction SilentlyContinue
if ($procs) { $procs | Stop-Process -Force; Start-Sleep -Seconds 2; Info "已结束残留 Trunkline 进程" }

# ---------- 暂存 runtime，避 mvn clean 删除 ----------
if (Test-Path $RUNTIME) {
    if (Test-Path $RT_STASH) { Err "$RT_STASH 已存在异常残留，请人工处理后重试"; exit 1 }
    Move-Item $RUNTIME $RT_STASH
    Info "runtime 已暂存到 $RT_STASH"
}

# ---------- 构建 jar ----------
if ($SkipBuild) {
    Warn "跳过构建，复用现有 jar"
} else {
    Info "clean package（跳过前端重复构建与测试）..."
    # 清会话端口劫持（当前进程内）
    Remove-Item Env:SERVER__PORT -ErrorAction SilentlyContinue
    Remove-Item Env:SERVER__HOST -ErrorAction SilentlyContinue
    # 把 target/ 内的 dist-installer* 备份移出 target/（否则 clean 会因 exe 文件锁失败）
    New-Item -ItemType Directory -Force -Path $STASH_OUT | Out-Null
    Get-ChildItem $TARGET -Directory | Where-Object { $_.Name -like "dist-installer*" } | ForEach-Object {
        $destName = "$($_.Name)-$(Get-Date -Format HHmmss)"
        try { Move-Item $_.FullName (Join-Path $STASH_OUT $destName) -Force; Info "已移出被锁备份: $($_.Name)" }
        catch { Warn "移出失败（可能已无残留）: $($_.Name)" }
    }
    & "$PROJECT_ROOT\mvnw.cmd" clean package -DskipTests -Dfrontend.build.skip=true -q
    if ($LASTEXITCODE -ne 0) { Err "mvnw clean package 失败（退出码 $LASTEXITCODE）"; exit 1 }
}
$JAR_SRC = Join-Path $TARGET $JAR_NAME
if (-not (Test-Path $JAR_SRC)) { Err "缺少 $JAR_SRC，请先不加 -SkipBuild 运行一次"; exit 1 }
Ok "应用 jar 就绪"

# ---------- 移回或新建 runtime ----------
if (Test-Path $RT_STASH) {
    Move-Item $RT_STASH $RUNTIME
    Info "runtime 已移回 target/installer-runtime"
}
if (-not (Test-Path $RUNTIME)) {
    Info "jlink 生成宽模块 runtime（约 1-2 分钟）..."
    & $JLINK --add-modules $MODULES --module-path (Join-Path $JavaHome "jmods") `
        --output $RUNTIME --strip-debug --no-header-files --no-man-pages --compress=2
    if ($LASTEXITCODE -ne 0) { Err "jlink 失败"; exit 1 }
}
Ok "runtime 就绪"

# ---------- 准备 app-input ----------
New-Item -ItemType Directory -Force -Path $APP_INPUT | Out-Null
Copy-Item $JAR_SRC (Join-Path $APP_INPUT $JAR_NAME) -Force

# ---------- jpackage app-image ----------
if (Test-Path $DEST) { Move-Item $DEST (Join-Path $STASH_OUT "dist-installer-rebuild-$(Get-Date -Format HHmmss)") -Force -ErrorAction SilentlyContinue }
Info "jpackage app-image ..."
# 桌面便携版有意保留固定堆：本地桌面没有容器 limits，MaxRAMPercentage 会按宿主机
# 总内存放大堆；768m 上限可避免单机占用过高。生产容器镜像已移除 -Xms/-Xmx。
& $JPACKAGE --type app-image `
    --name Trunkline --app-version $APP_VERSION --vendor "Trunkline Team" `
    --input $APP_INPUT --dest $DEST `
    --main-jar $JAR_NAME `
    --runtime-image $RUNTIME `
    --java-options "-Dserver.port=8080 -Dtrunkline.desktop.open-browser=true -Xms256m -Xmx768m" `
    --win-console
if ($LASTEXITCODE -ne 0) { Err "jpackage 失败"; exit 1 }
Ok "app-image 就绪"

# ---------- 写入使用说明 ----------
$readme = @'
========================================
  Trunkline 智能路由模型网关 · 本地版
  版本：0.1.0  |  适用：Windows 10 / 11（64 位）
========================================

【三步开始使用】

1. 解压
   把整个 Trunkline 文件夹解压到「文档」「下载」等你自己能修改的目录。
   （不要放在 C:\Program Files 这类系统目录，普通用户没权限写入）

2. 双击启动
   进入文件夹，双击 Trunkline.exe。会弹出一个黑色命令行窗口——这是正常的，
   说明服务正在运行。请不要关闭它；关掉它 = 停止服务。

3. 浏览器自动打开
   服务启动完成后（约 10 秒），会自动用你的默认浏览器打开管理页面。
   如果没有自动打开，请手动在浏览器输入：  http://localhost:8080/admin/
   首次使用会进入「初始化向导」，按提示分三步：
     ① 选择数据源（默认本地 H2 数据库，直接下一步即可）
     ② 设置管理员账号和密码
     ③ 完成，进入控制台

【日常使用】
- 启动：双击 Trunkline.exe      停止：关闭黑色命令行窗口
- 访问：http://localhost:8080/admin/
- 数据：本文件夹下的 data 目录（请勿删除，删了会丢配置/密钥/历史）
- 换电脑/备份：整个文件夹复制到新电脑即可，数据跟着走

【常见问题】
Q：黑窗口一闪而过/没反应？ A：多半放在了系统保护目录，移到「文档/下载」再双击。
Q：浏览器没自动打开？      A：等约 10 秒，手动访问 http://localhost:8080/admin/，不影响使用。
Q：提示 8080 被占用？      A：关闭占用 8080 的程序后重试。

【安全提示】
- 本程序只在本机运行，数据不上传任何服务器。
- 请勿把本文件夹或 data 目录上传到公开网络。
- 首次初始化请设置足够复杂的管理员密码并妥善保管。
'@
$readmePath = Join-Path $APP_DIR "使用说明.txt"
[System.IO.File]::WriteAllText($readmePath, $readme, [System.Text.UTF8Encoding]::new($false))

# ---------- 清理测试产生的数据，不污染交付 ----------
foreach ($d in @("data", "logs")) {
    $p = Join-Path $APP_DIR $d
    if (Test-Path $p) { Move-Item $p (Join-Path $TARGET "test-$d-$(Get-Date -Format HHmmss)"); Warn "已移出测试 $d" }
}

# ---------- 打 zip ----------
$zip = Join-Path $DEST "Trunkline-$APP_VERSION-win-x64-portable.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Info "压缩为 zip ..."
Compress-Archive -Path $APP_DIR -DestinationPath $zip -CompressionLevel Optimal
$item = Get-Item $zip
Ok "产物: $zip"
Ok ("大小: {0:N1} MB" -f ($item.Length / 1MB))

# ---------- 清理中间产物 ----------
Remove-Item $APP_INPUT -Recurse -Force -ErrorAction SilentlyContinue
if (Test-Path $STASH_OUT) { Remove-Item $STASH_OUT -Recurse -Force -ErrorAction SilentlyContinue; Info "已清理临时暂存目录" }
Info "完成。分发时把该 zip 发给用户即可。"
