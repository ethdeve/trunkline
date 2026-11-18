#!/bin/bash
# =============================================================================
# Trunkline 跨平台便携版组装脚本 — 用「目标平台官方 JRE」组装，可在任意构建机上运行
#
# 用法：./scripts/build/package-portable-cross.sh \
#           --os linux|mac --arch x64|aarch64|arm64 --jre /path/to/jre.tar.gz [--skip-build]
#   --os      目标操作系统：linux / mac
#   --arch    linux: x64 | aarch64 ; mac: arm64(Apple Silicon) | x64(Intel)
#   --jre     该平台官方 Temurin JRE 21 的 tar.gz（从 api.adoptium.net 下载）
#   --skip-build  复用 target/trunkline-0.1.0.jar，不重新构建
#
# 产物：target/dist-linux/Trunkline-0.1.0-linux-<arch>.tar.gz
#       target/dist-mac/Trunkline-0.1.0-macos-<arch>.tar.gz
#
# 【为什么不用 jlink 交叉生成】
#   jlink 产出的 runtime 二进制跟随「构建机 JDK」架构，无法在另一平台运行；
#   且 jpackage 自动裁剪会漏 java.naming（Logback JNDI 起不来）。
#   直接用目标平台官方 JRE（完整标准模块集）作 runtime，平台正确、模块齐全。
#
# 【Mac 为什么只出 tar.gz，不出 dmg】
#   dmg 需 macOS 的 hdiutil，Windows/Linux 构建机无法生成。
#   Mac 用户双击 tar.gz 解压后运行 start.command 即可，功能等价。
# =============================================================================
set -e

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
info() { echo -e "${BLUE}[INFO]${NC} $1"; }
ok()   { echo -e "${GREEN}[OK]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
err()  { echo -e "${RED}[ERROR]${NC} $1"; }

# 构建临时目录不直接删（本机安全删除钩子会拦截批量文件删除）；
# 复用时改名让位，旧目录留在 target/ 由构建产物目录统一管理。
stash_dir() {
    local dir="$1"
    [ -d "$dir" ] && mv "$dir" "${dir}-prev-$(date +%s)"
}

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# 版本号从 pom.xml 顶层 <version> 读取，避免打包脚本与 Maven 坐标脱钩。
# parent 内的 <version> 缩进 8 空格，顶层为 4 空格，故只匹配后者。
APP_VERSION="$(sed -n 's/^    <version>\([^<]*\)<\/version>[[:space:]]*$/\1/p' "$PROJECT_ROOT/pom.xml" | head -n1)"
if [[ ! "$APP_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.]+)?$ ]]; then
    err "无法从 pom.xml 解析顶层 <version>（得到：'${APP_VERSION}'）"
    exit 1
fi
JAR_NAME="trunkline-${APP_VERSION}.jar"
TARGET_OS=""; TARGET_ARCH=""; JRE_ARCHIVE=""; SKIP_BUILD=0

while [ $# -gt 0 ]; do
    case "$1" in
        --os) TARGET_OS="$2"; shift 2 ;;
        --arch) TARGET_ARCH="$2"; shift 2 ;;
        --jre) JRE_ARCHIVE="$2"; shift 2 ;;
        --skip-build) SKIP_BUILD=1; shift ;;
        -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
        *) err "未知参数: $1"; exit 1 ;;
    esac
done

[ "$TARGET_OS" = "linux" ] || [ "$TARGET_OS" = "mac" ] || { err "--os 必须是 linux 或 mac"; exit 1; }
[ -n "$TARGET_ARCH" ] || { err "请用 --arch 指定架构"; exit 1; }
[ -f "$JRE_ARCHIVE" ] || { err "找不到 JRE 压缩包: $JRE_ARCHIVE"; exit 1; }

cd "$PROJECT_ROOT"

# --- 构建 jar（除非跳过）---
if [ "$SKIP_BUILD" -eq 0 ]; then
    info "构建应用 jar（clean package）..."
    unset SERVER__PORT SERVER__HOST
    if [ -x ./mvnw ]; then ./mvnw clean package -DskipTests -Dfrontend.build.skip=true -q
    else bash ./mvnw clean package -DskipTests -Dfrontend.build.skip=true -q; fi
else
    warn "跳过构建，复用现有 jar"
fi
JAR_SRC="$PROJECT_ROOT/target/$JAR_NAME"
[ -f "$JAR_SRC" ] || { err "缺少 $JAR_SRC"; exit 1; }
ok "应用 jar 就绪 ($(du -sh "$JAR_SRC" | cut -f1))"

# --- 组装目录 ---
if [ "$TARGET_OS" = "linux" ]; then
    OUT_DIR="$PROJECT_ROOT/target/dist-linux"
    BUILD_ROOT="$PROJECT_ROOT/target/dist-linux/build-${TARGET_ARCH}-$(date +%s)"
    OUT_NAME="Trunkline-${APP_VERSION}-linux-${TARGET_ARCH}.tar.gz"
else
    OUT_DIR="$PROJECT_ROOT/target/dist-mac"
    BUILD_ROOT="$PROJECT_ROOT/target/dist-mac/build-${TARGET_ARCH}-$(date +%s)"
    OUT_NAME="Trunkline-${APP_VERSION}-macos-${TARGET_ARCH}.tar.gz"
fi
STAGE="$BUILD_ROOT/Trunkline"
mkdir -p "$STAGE/app"

# --- 解压目标平台 JRE 作为 runtime ---
EXTRACT="$BUILD_ROOT/jre-extract"
mkdir -p "$EXTRACT"
# --no-same-owner：避免解压时试图保留原属主；符号链接按 tar 记录还原（mac JRE 含 .dylib 链接）
tar -xzf "$JRE_ARCHIVE" -C "$EXTRACT" --no-same-owner
# 定位含 bin/java 的 JRE 根：Linux 布局是 <top>/bin/java；macOS 布局是 <top>/Contents/Home/bin/java
JRE_TOP="$(find "$EXTRACT" -mindepth 1 -maxdepth 1 -type d | head -1)"
[ -n "$JRE_TOP" ] || { err "JRE 解压后未找到顶层目录"; exit 1; }
JRE_HOME="$JRE_TOP"
if [ -d "$JRE_TOP/Contents/Home" ]; then
    JRE_HOME="$JRE_TOP/Contents/Home"   # macOS .jre bundle 布局
fi
mv "$JRE_HOME" "$STAGE/runtime"
rm -rf "$EXTRACT" 2>/dev/null || true
# 平台可执行体存在性校验
[ -f "$STAGE/runtime/bin/java" ] || { err "runtime/bin/java 不存在，JRE 结构异常"; exit 1; }
# macOS 官方 JRE 的 lib 内含符号链接（如 libjli.dylib / libjvm.dylib），tar 已按记录还原，此处仅提示
info "JRE 布局: $([ "$JRE_HOME" != "$JRE_TOP" ] && echo 'Contents/Home (macOS bundle)' || echo '标准布局')"
ok "内置 runtime 就绪 ($(du -sh "$STAGE/runtime" | cut -f1))"

cp "$JAR_SRC" "$STAGE/app/$JAR_NAME"
[ -f "$PROJECT_ROOT/logo/brand-mark.svg" ] && cp "$PROJECT_ROOT/logo/brand-mark.svg" "$STAGE/brand-mark.svg"

# --- 启动脚本 ---
if [ "$TARGET_OS" = "linux" ]; then
cat > "$STAGE/start.sh" <<'EOF'
#!/bin/sh
cd "$(dirname "$0")" || exit 1
if [ ! -x ./runtime/bin/java ]; then
  echo "错误：未找到内置 runtime（runtime/bin/java），请确认压缩包完整并赋予执行权限。"
  exit 1
fi
JAR=$(ls ./app/trunkline-*.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
  echo "错误：未在 app/ 下找到 trunkline-*.jar。"
  exit 1
fi
chmod +x ./runtime/bin/java 2>/dev/null || true
unset SERVER__PORT SERVER__HOST
echo "Trunkline 正在启动，稍后浏览器会自动打开 http://localhost:8080/admin/"
echo "按 Ctrl+C 或关闭终端即停止服务。"
exec ./runtime/bin/java -Dtrunkline.desktop.open-browser=true -jar "$JAR" --server.port=8080 "$@"
EOF
chmod +x "$STAGE/start.sh"

cat > "$STAGE/install-desktop-entry.sh" <<'DESKTOP_EOF'
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
DESKTOP_EOF
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
   - 提示“权限不够 / Permission denied”：执行 chmod +x start.sh runtime/bin/java 后重试。
   - 端口 8080 被占用：先停掉占用该端口的程序，或改 start.sh 里的 --server.port。
   - 浏览器没自动弹：手动打开 http://localhost:8080/admin/ 即可，不影响使用。
   - 无图形界面的服务器：浏览器不会弹出，按终端提示的网址用其他机器访问。
   注意：服务启动后请保持终端窗口打开；关闭即停止服务。
EOF2

else  # mac
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
chmod +x ./runtime/bin/java 2>/dev/null || true
unset SERVER__PORT SERVER__HOST
echo "Trunkline 正在启动，稍后浏览器会自动打开 http://localhost:8080/admin/"
echo "按 Ctrl+C 或关闭终端窗口即停止服务。"
exec ./runtime/bin/java -Dtrunkline.desktop.open-browser=true -jar "$JAR" --server.port=8080 "$@"
EOF
chmod +x "$STAGE/start.command"

cat > "$STAGE/使用说明.txt" <<'EOF2'
Trunkline 智能路由模型网关 · macOS 便携版
================================================
一、启动
   1. 双击 tar.gz 解压，把 Trunkline 文件夹放到「应用程序 / 下载 / 文稿」
      等你有写权限的位置（不要在只读的挂载盘里运行）。
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
   - 提示“权限不够”：在终端执行 chmod +x "路径/start.command" "路径/runtime/bin/java"。
   - 端口 8080 被占用：停掉占用程序，或改 start.command 里的 --server.port。
   - 浏览器没自动弹：手动打开 http://localhost:8080/admin/ 即可，不影响使用。
   注意：服务启动后请保持终端窗口打开；关闭即停止服务。

   说明：本版用 start.command 而非双击 .app 启动，是因为 .app 的工作目录是
   只读的根目录，无法保存数据；用启动脚本可让数据稳定落在文件夹内。
EOF2
fi

# --- 打 tar.gz ---
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/$OUT_NAME"
info "生成 tar.gz ..."
tar -czf "$OUT" -C "$BUILD_ROOT" Trunkline
ok "产物: $OUT ($(du -sh "$OUT" | cut -f1))"

# --- 校验产物 ---
echo "--- 产物结构校验 ---"
tar -tzf "$OUT" | grep -E "Trunkline/(app/trunkline-|runtime/bin/java|使用说明|start\.)" | head -10
info "完成（构建临时目录保留于 $BUILD_ROOT）。"
