#!/bin/bash
# =============================================================================
# Trunkline 桌面便携版打包脚本 — macOS（dmg + tar.gz，内置 JRE，双击即用）
#
# 用法：./scripts/build/package-desktop-mac.sh [--skip-build] [--arch arm64|x64]
#   --skip-build  复用 target/trunkline-0.1.0.jar，不重新构建
#   --arch        目标架构 arm64(Apple Silicon) / x64(Intel)；默认取当前机器
#
# 产物：target/dist-mac/Trunkline-0.1.0-macos-<arch>.dmg
#       target/dist-mac/Trunkline-0.1.0-macos-<arch>.tar.gz
#
# 【为什么不是双击 .app】
#   macOS Finder 双击 .app 时进程 cwd 是 /（根目录，普通用户不可写），
#   而本应用数据全用相对路径 ./data，会创建失败。故采用「内置 runtime +
#   start.command 启动脚本」形态：数据落解压目录、可写、无需改 Java、无需 root。
#   start.command 双击即在“终端”运行（等效 Windows 双击 exe 弹黑框），体验一致。
#
# 【分发提示】dmg 需在 macOS 上用 hdiutil 生成；本脚本在非 macOS 上会跳过 dmg、
#   仅产出 tar.gz。runtime 架构跟随构建机 JDK，要出另一架构包请在对应机器构建。
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
MODULES="java.se,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.management,jdk.management.agent,jdk.attach,java.instrument,jdk.localedata,jdk.jcmd"

SKIP_BUILD=0
ARCH=""
while [ $# -gt 0 ]; do
    case "$1" in
        --skip-build) SKIP_BUILD=1; shift ;;
        --arch) ARCH="$2"; shift 2 ;;
        -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
        *) err "未知参数: $1"; exit 1 ;;
    esac
done

# --- 目标架构 ---
MACHINE="$(uname -m)"
if [ -z "$ARCH" ]; then
    case "$MACHINE" in
        x86_64|amd64)   ARCH="x64" ;;
        aarch64|arm64)  ARCH="arm64" ;;
        *) err "无法识别架构 $MACHINE，请用 --arch arm64|x64 指定"; exit 1 ;;
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
info "JDK: $JH (os.arch=$JH_ARCH)  目标架构: $ARCH"
if { [ "$ARCH" = "arm64" ] && [ "$JH_ARCH" != "aarch64" ]; } || \
   { [ "$ARCH" = "x64" ] && [ "$JH_ARCH" != "amd64" ] && [ "$JH_ARCH" != "x86_64" ]; }; then
    warn "目标架构 $ARCH 与构建机 JDK 架构 $JH_ARCH 不一致，产物可能无法运行。请在对应机器构建。"
fi

cd "$PROJECT_ROOT"

# --- 构建 jar ---
build_jar() {
    if [ -x ./mvnw ]; then ./mvnw clean package -DskipTests -Dfrontend.build.skip=true -q
    else bash ./mvnw clean package -DskipTests -Dfrontend.build.skip=true -q; fi
}
if [ "$SKIP_BUILD" -eq 0 ]; then
    info "构建应用 jar（clean package）..."
    build_jar
else
    warn "跳过构建，复用现有 jar"
fi
JAR_SRC="$PROJECT_ROOT/target/$JAR_NAME"
[ -f "$JAR_SRC" ] || { err "缺少 $JAR_SRC，请先不加 --skip-build 运行一次"; exit 1; }
ok "应用 jar 就绪 ($(du -sh "$JAR_SRC" | cut -f1))"

# --- jlink runtime ---
BUILD_ROOT="$PROJECT_ROOT/target/dist-mac/build"
STAGE="$BUILD_ROOT/Trunkline"
rm -rf "$BUILD_ROOT"; mkdir -p "$STAGE/app"
info "jlink 生成宽模块 runtime（约 1-2 分钟）..."
"$JH/bin/jlink" --add-modules "$MODULES" --module-path "$JH/jmods" \
    --output "$STAGE/runtime" --strip-debug --no-header-files --no-man-pages --compress=2
ok "runtime 就绪 ($(du -sh "$STAGE/runtime" | cut -f1))"

# --- 组装应用 ---
cp "$JAR_SRC" "$STAGE/app/$JAR_NAME"
[ -f "$PROJECT_ROOT/logo/brand-mark.svg" ] && cp "$PROJECT_ROOT/logo/brand-mark.svg" "$STAGE/brand-mark.svg"

cat > "$STAGE/start.command" <<'EOF'
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
unset SERVER__PORT SERVER__HOST
echo "Trunkline 正在启动，稍后浏览器会自动打开 http://localhost:8080/admin/"
echo "按 Ctrl+C 或关闭终端窗口即停止服务。"
exec ./runtime/bin/java -jar "$JAR" --server.port=8080 "$@"
EOF
chmod +x "$STAGE/start.command"

cat > "$STAGE/使用说明.txt" <<'EOF2'
Trunkline 智能路由模型网关 · macOS 便携版
================================================
一、启动
   1. 双击 dmg 挂载后，把 Trunkline 文件夹拖到「应用程序 / 下载 / 文稿」
      等你有写权限的位置（不要留在只读的挂载盘里运行）。
   2. 双击文件夹内的 start.command（首次可能需在“终端”里右键→打开）。
      稍等约 10 秒，浏览器会自动打开 http://localhost:8080/admin/
      若未自动打开，请手动访问该地址。
   3. 首次使用按「初始化向导」三步操作（默认本地 H2 数据库，直接下一步即可）。

二、停止
   在 start.command 打开的终端窗口按 Ctrl+C，或关闭该窗口。

三、数据
   配置、模型、密钥、历史记录保存在本文件夹的 data/ 下。
   请勿删除；备份或迁移时连同整个 Trunkline 文件夹一起复制即可。

四、常见问题
   - 提示“无法打开，因为来自身份不明的开发者”：
     在终端执行（把路径换成你的实际位置）：
       xattr -dr com.apple.quarantine "/路径/Trunkline"
     然后重新双击 start.command。
   - 端口 8080 被占用：停掉占用程序，或改 start.command 里的 --server.port。
   - 浏览器没自动弹：手动打开 http://localhost:8080/admin/ 即可，不影响使用。
   注意：服务启动后请保持终端窗口打开；关闭即停止服务。

   说明：本版用 start.command 而非双击 .app 启动，是因为 .app 的工作目录是
   只读的根目录，无法保存数据；用启动脚本可让数据稳定落在文件夹内。
EOF2

# --- 打 tar.gz ---
OUT_DIR="$PROJECT_ROOT/target/dist-mac"
mkdir -p "$OUT_DIR"
TGZ="$OUT_DIR/Trunkline-${APP_VERSION}-macos-${ARCH}.tar.gz"
info "生成 tar.gz ..."
tar -czf "$TGZ" -C "$BUILD_ROOT" Trunkline
ok "产物: $TGZ ($(du -sh "$TGZ" | cut -f1))"

# --- 打 dmg（仅 macOS）---
if command -v hdiutil >/dev/null 2>&1; then
    DMG="$OUT_DIR/Trunkline-${APP_VERSION}-macos-${ARCH}.dmg"
    info "生成 dmg ..."
    rm -f "$DMG"
    hdiutil create -volname "Trunkline" -srcfolder "$BUILD_ROOT" -ov -format UDZO "$DMG" >/dev/null
    ok "产物: $DMG ($(du -sh "$DMG" | cut -f1))"
else
    warn "当前系统无 hdiutil（非 macOS），跳过 dmg，仅产出 tar.gz。请在 macOS 上重跑以生成 dmg。"
fi

# 清理中间构建目录
rm -rf "$BUILD_ROOT"
info "完成。"
