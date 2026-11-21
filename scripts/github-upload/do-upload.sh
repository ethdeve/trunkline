#!/bin/bash
# 上传 6 个免安装包到 GitHub Release v0.1.0 (id=449373708)
TOKEN=$(cat /c/Users/Administrator/AppData/Local/Temp/gh_token.txt)
RELEASE_ID=449373708
REPO="wjy2027/trunkline"
LOG="/c/Users/Administrator/AppData/Local/Temp/gh_upload.log"
: > "$LOG"

cd "D:/wdx/wjy/trunkline" || { echo "CD FAILED" >> "$LOG"; exit 1; }

upload_one() {
  local f="$1"
  local name
  name=$(basename "$f")
  local ct="application/zip"
  case "$name" in *.tar.gz) ct="application/gzip";; esac
  local resp="/c/Users/Administrator/AppData/Local/Temp/up_resp_${RANDOM}.json"
  echo "=== UPLOAD $name (Content-Type=$ct) ===" >> "$LOG"
  local code
  code=$(curl -s -o "$resp" -w "%{http_code}" --max-time 900 --retry 2 --retry-delay 3 \
    -X POST \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "Content-Type: $ct" \
    --data-binary @"$f" \
    "https://uploads.github.com/repos/$REPO/releases/$RELEASE_ID/assets?name=$name")
  echo "  HTTP=$code" >> "$LOG"
  python -c 'import json,sys; d=json.load(open(sys.argv[1],encoding="utf-8")); print("  RESULT: id=",d.get("id")," name=",d.get("name")," size=",d.get("size")," msg=",d.get("message",""))' "$resp" >> "$LOG" 2>&1 || echo "  RAW: $(head -c 300 "$resp")" >> "$LOG"
}

upload_one "target/dist-installer/Trunkline-0.1.0-win-x64-portable.zip"
upload_one "target/dist-win-arm64/Trunkline-0.1.0-win-arm64-portable.zip"
upload_one "target/dist-linux/Trunkline-0.1.0-linux-x64.tar.gz"
upload_one "target/dist-linux/Trunkline-0.1.0-linux-aarch64.tar.gz"
upload_one "target/dist-mac/Trunkline-0.1.0-macos-arm64.tar.gz"
upload_one "target/dist-mac/Trunkline-0.1.0-macos-x64.tar.gz"

echo "ALL_UPLOADS_DONE" >> "$LOG"
