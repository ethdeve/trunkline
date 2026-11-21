#!/usr/bin/env bash
# 通过 GitHub REST API 上传 6 个安装包到 v0.1.0 Release
# 用法： bash trunkline-my-doc/product/upload-release-assets.sh
# token 通过环境变量 GITHUB_TOKEN 或 GITHUB_TOKEN_FILE 提供（脚本内也会读取 _TOKEN_PLACEHOLDER）
# 走 api.github.com / uploads.github.com，绕开网页上传报错。

set -euo pipefail

REPO="wjy2027/trunkline"
TAG="v0.1.0"
API="https://api.github.com"
UPLOAD="https://uploads.github.com"

# ===== token：优先环境变量，其次同目录 token.txt，最后占位符 =====
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -n "${GITHUB_TOKEN:-}" ]; then
  TOKEN="$GITHUB_TOKEN"
elif [ -f "$SCRIPT_DIR/token.txt" ]; then
  TOKEN=$(tr -d '\r\n' < "$SCRIPT_DIR/token.txt")
else
  TOKEN="__TOKEN_PLACEHOLDER__"
fi
AUTH="Authorization: Bearer $TOKEN"

echo "==> 校验 token"
USER_JSON=$(curl -s -H "$AUTH" "$API/user")
LOGIN=$(printf '%s' "$USER_JSON" | python -c "import sys,json; d=json.load(sys.stdin); print(d.get('login',''))" 2>/dev/null || true)
if [ -z "$LOGIN" ]; then
  echo "!! TOKEN 无效：$USER_JSON"
  exit 1
fi
echo "    登录用户: $LOGIN"

echo "==> 查询 $TAG 的 release"
REL_JSON=$(curl -s -H "$AUTH" "$API/repos/$REPO/releases/tags/$TAG")
REL_ID=$(printf '%s' "$REL_JSON" | python -c "import sys,json; d=json.load(sys.stdin); print(d.get('id',''))" 2>/dev/null || true)

if [ -n "$REL_ID" ]; then
  echo "    找到已发布 release id=$REL_ID，直接上传附件"
else
  echo "!! 未找到 tag=$TAG 的 release。尝试查找草稿并发布："
  DRAFTS=$(curl -s -H "$AUTH" "$API/repos/$REPO/releases?per_page=100")
  # 取第一个草稿的 id（即你网页里保存的那个）
  DRAFT_ID=$(printf '%s' "$DRAFTS" | python -c "
import sys,json
d=json.load(sys.stdin)
if isinstance(d,list):
    drafts=[r for r in d if r.get('draft')]
    print(drafts[0].get('id','') if drafts else '')
" 2>/dev/null || true)
  if [ -z "$DRAFT_ID" ]; then
    echo "!! 没找到草稿。请先在 GitHub 网页创建 release 草稿，或确认 tag 是否已推送。"
    printf '%s\n' "$DRAFTS" | head -c 500
    exit 1
  fi
  echo "    草稿 id=$DRAFT_ID，发布并绑定 tag=$TAG ..."
  PUB=$(curl -s -X PATCH -H "$AUTH" -H "Accept: application/vnd.github+json" \
    -H "Content-Type: application/json" \
    -d "{\"tag_name\":\"$TAG\",\"draft\":false,\"prerelease\":false}" \
    "$API/repos/$REPO/releases/$DRAFT_ID")
  REL_ID=$(printf '%s' "$PUB" | python -c "import sys,json; d=json.load(sys.stdin); print(d.get('id',''))" 2>/dev/null || true)
  if [ -z "$REL_ID" ]; then
    echo "!! 发布草稿失败：$PUB"
    exit 1
  fi
  echo "    发布成功，release id=$REL_ID"
fi

cd "D:/wdx/wjy/trunkline"
FILES=(
  "target/dist-installer/Trunkline-0.1.0-win-x64-portable.zip"
  "target/dist-win-arm64/Trunkline-0.1.0-win-arm64-portable.zip"
  "target/dist-linux/Trunkline-0.1.0-linux-x64.tar.gz"
  "target/dist-linux/Trunkline-0.1.0-linux-aarch64.tar.gz"
  "target/dist-mac/Trunkline-0.1.0-macos-arm64.tar.gz"
  "target/dist-mac/Trunkline-0.1.0-macos-x64.tar.gz"
)

for f in "${FILES[@]}"; do
  [ -f "$f" ] || { echo "!! 文件不存在: $f"; continue; }
  name=$(basename "$f")
  echo "==> 上传 $name ..."
  RESP=$(curl -s --retry 3 --retry-delay 2 \
    -H "$AUTH" \
    -H "Content-Type: application/octet-stream" \
    --data-binary @"$f" \
    "$UPLOAD/repos/$REPO/releases/$REL_ID/assets?name=$name")
  printf '%s' "$RESP" | python -c "
import sys,json
d=json.load(sys.stdin)
print('  OK:', d.get('name'), round(d.get('size',0)/1024/1024,1),'MB') if 'id' in d else print('  FAIL:', d.get('message'))
" 2>/dev/null || echo "  FAIL: 响应解析失败: $(printf '%s' "$RESP" | head -c 200)"
done

echo "==> 完成：https://github.com/$REPO/releases/tag/$TAG"
