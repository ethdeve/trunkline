#!/bin/bash
# =============================================================================
# Trunkline 桌面便携版打包脚本 — Linux（tar.gz，内置 JRE，解压即用）
#
# 用法：./scripts/build/package-desktop-linux.sh [--skip-build] [--arch x64|aarch64]
#   --skip-build  复用 target/trunkline-0.1.0.jar，不重新构建（打包流程调试用）
#   --arch        目标架构；默认取当前机器 uname -m
#
# 产物：target/dist-linux/Trunkline-0.1.0-linux-<arch>.tar.gz
#
# 设计说明：
#   - 内置 jlink runtime（宽模块集）+ 极简 start.sh，无需用户安装 JDK。
#   - 数据落「解压目录」的 ./data（可写），不改 Java、无需 root。
#   - 自动开浏览器由 Java 层 DesktopBrowserLauncher 的 xdg-open 分支负责；
#     无桌面服务器时降级为在终端打印地址。
#   - runtime 架构跟随构建机 JDK：要出另一架构包，请在对应架构机器上跑。
# =============================================================================
set -e

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
info() { echo -e "${BLUE}[INFO]${NC} $1"; }
ok()   { echo -e "${GREEN}[OK]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
err()  { echo -e "${RED}[ERROR]${NC} $1"; }

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# 版本号从 pom.xml 顶层 <version> 读取，避免打包脚本与 Maven 坐标脱钩。
# parent 内的 <version> 缩进 8 空格，顶层为 4 空格，故只匹配后者。
APP_VERSION="$(sed -n 's/^    <version>\([^<]*\)<\/version>[[:space:]]*$/\1/p' "$PROJECT_ROOT/pom.xml" | head -n1)"
if [[ ! "$APP_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.]+)?$ ]]; then
    err "无法从 pom.xml 解析顶层 <version>（得到：'${APP_VERSION}'）"
    exit 1
fi
JAR_NAME="trunkline-${APP_VERSION}.jar"
# 宽模块集：覆盖 java.se 全标准模块 + Netty/TLS/instrumentation 所需模块。
# 切勿用 jpackage 的自动裁剪——它对 Spring Boot fat jar 依赖分析不全，会漏 java.naming 起不来。
MODULES="java.se,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.management,jdk.management.agent,jdk.attach,java.instrument,jdk.localedata,jdk.jcmd"

SKIP_BUILD=0
ARCH=""
while [ $# -gt 0 ]; do
    case "$1" in
        --skip-build) SKIP_BUILD=1; shift ;;
        --arch) ARCH="$2"; shift 2 ;;
        -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
        *) err "未知参数: $1"; exit 1 ;;
    esac
done

# --- 目标架构 ---
MACHINE="$(uname -m)"
if [ -z "$ARCH" ]; then
    case "$MACHINE" in
        x86_64|amd64)   ARCH="x64" ;;
        aarch64|arm64)  ARCH="aarch64" ;;
        *) err "无法识别架构 $MACHINE，请用 --arch x64|aarch64 指定"; exit 1 ;;
    esac
fi

# --- 定位 JDK / jlink ---
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/jlink" ]; then
    JH="$JAVA_HOME"
elif command -v jlink >/dev/null 2>&1; then
    JH="$(dirname "$(dirname "$(command -v jlink)")")"
else
    err "未找到 jlink。请先安装 JDK 21 并设置 JAVA_HOME。"; exit 1
fi
JH_ARCH="$("$JH/bin/java" -XshowSettings:properties -version 2>&1 | grep -m1 'os.arch' | awk '{print $3}')"
info "JDK: $JH (os.arch=$JH_ARCH)"
if { [ "$ARCH" = "aarch64" ] && [ "$JH_ARCH" != "aarch64" ]; } || \
   { [ "$ARCH" = "x64" ] && [ "$JH_ARCH" != "amd64" ] && [ "$JH_ARCH" != "x86_64" ]; }; then
    warn "目标架构 $ARCH 与构建机 JDK 架构 $JH_ARCH 不一致：jlink runtime 跟随 JDK 架构，产物可能无法在 $ARCH 上运行。请在对应机器上构建。"
fi

cd "$PROJECT_ROOT"

# --- 构建 jar（除非跳过）---
build_jar() {
    if [ -x ./mvnw ]; then
        ./mvnw clean package -DskipTests -Dfrontend.build.skip=true -q
    else
        bash ./mvnw clean package -DskipTests -Dfrontend.build.skip=true -q
    fi
}
if [ "$SKIP_BUILD" -eq 0 ]; then
    info "构建应用 jar（clean package，跳过前端重复构建与测试）..."
    build_jar
else
    warn "跳过构建，复用现有 jar"
fi
JAR_SRC="$PROJECT_ROOT/target/$JAR_NAME"
[ -f "$JAR_SRC" ] || { err "缺少 $JAR_SRC，请先不加 --skip-build 运行一次"; exit 1; }
ok "应用 jar 就绪 ($(du -sh "$JAR_SRC" | cut -f1))"

# --- jlink 生成内置 runtime ---
BUILD_ROOT="$PROJECT_ROOT/target/dist-linux/build"
STAGE="$BUILD_ROOT/Trunkline"
rm -rf "$BUILD_ROOT"; mkdir -p "$STAGE/app"
info "jlink 生成宽模块 runtime（约 1-2 分钟）..."
"$JH/bin/jlink" --add-modules "$MODULES" --module-path "$JH/jmods" \
    --output "$STAGE/runtime" --strip-debug --no-header-files --no-man-pages --compress=2
ok "runtime 就绪 ($(du -sh "$STAGE/runtime" | cut -f1))"

# --- 组装应用 ---
cp "$JAR_SRC" "$STAGE/app/$JAR_NAME"
[ -f "$PROJECT_ROOT/logo/brand-mark.svg" ] && cp "$PROJECT_ROOT/logo/brand-mark.svg" "$STAGE/brand-mark.svg"

cat > "$STAGE/start.sh" <<'EOF'
#!/bin/sh
cd "$(dirname "$0")" || exit 1
if [ ! -x ./runtime/bin/java ]; then
  echo "错误：未找到内置 runtime（runtime/bin/java），请确认压缩包完整。"
  exit 1
fi
JAR=$(ls ./app/trunkline-*.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
  echo "错误：未在 app/ 下找到 trunkline-*.jar。"
  exit 1
fi
# 清除可能存在的服务端端口劫持，固定 8080
unset SERVER__PORT SERVER__HOST
echo "Trunkline 正在启动，稍后浏览器会自动打开 http://localhost:8080/admin/"
echo "按 Ctrl+C 或关闭终端即停止服务。"
exec ./runtime/bin/java -jar "$JAR" --server.port=8080 "$@"
EOF
chmod +x "$STAGE/start.sh"

cat > "$STAGE/install-desktop-entry.sh" <<'EOF'
#!/bin/sh
# 把 Trunkline 注册到当前用户的系统应用菜单（无需 root）
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$HOME/.local/share/applications"
mkdir -p "$APP_DIR"
cat > "$APP_DIR/trunkline.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Trunkline
Comment=智能路由模型网关
Exec=sh "$DIR/start.sh"
Icon=$DIR/brand-mark.svg
Terminal=true
Categories=Development;Network;
DESKTOP
chmod +x "$APP_DIR/trunkline.desktop"
echo "已注册桌面入口：$APP_DIR/trunkline.desktop"
echo "若图标未显示，执行：update-desktop-database $APP_DIR"
EOF
chmod +x "$STAGE/install-desktop-entry.sh"

cat > "$STAGE/使用说明.txt" <<'EOF2'
Trunkline 智能路由模型网关 · Linux 便携版
================================================
一、启动
   1. 解压本包到「主目录 / 下载 / 文档」等你有写权限的位置
      （不要放在 /usr、/opt 等系统目录）。
   2. 终端进入解压后的 Trunkline 目录，执行：  ./start.sh
      稍等约 10 秒，浏览器会自动打开 http://localhost:8080/admin/
      若未自动打开，请手动访问该地址。
   3. 首次使用按「初始化向导」三步操作（默认本地 H2 数据库，直接下一步即可）。

二、停止
   在运行 start.sh 的终端按 Ctrl+C，或关闭该终端窗口。

三、数据
   配置、模型、密钥、历史记录保存在本目录的 data/ 下。
   请勿删除；备份或迁移时连同整个 Trunkline 目录一起复制即可。

四、可选：加到桌面菜单
   执行  ./install-desktop-entry.sh
   之后可在系统应用菜单中搜索「Trunkline」启动。

五、常见问题
   - 端口 8080 被占用：先停掉占用该端口的程序，或改 start.sh 里的 --server.port。
   - 浏览器没自动弹：手动打开 http://localhost:8080/admin/ 即可，不影响使用。
   - 无图形界面的服务器：浏览器不会弹出，按终端提示的网址用其他机器访问。
   注意：服务启动后请保持终端窗口打开；关闭即停止服务。
EOF2

# --- 打 tar.gz ---
OUT_DIR="$PROJECT_ROOT/target/dist-linux"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/Trunkline-${APP_VERSION}-linux-${ARCH}.tar.gz"
info "生成 tar.gz ..."
tar -czf "$OUT" -C "$BUILD_ROOT" Trunkline
ok "产物: $OUT ($(du -sh "$OUT" | cut -f1))"

# 清理中间构建目录（保留 tar.gz）
rm -rf "$BUILD_ROOT"
info "完成。分发时把该 tar.gz 发给用户即可。"
