#!/usr/bin/env bash
# Trunkline TTV 自检脚本（社区版完善规划 §三 v0.2 追加任务）
# 度量：部署 → 登录 → 发出第一把密钥 → 第一句对话 的各步耗时与总 TTV
# 用法：
#   ./scripts/ttv-check.sh                          # 默认 http://localhost:8080
#   BASE_URL=http://host:8080 ./scripts/ttv-check.sh
#   TTV_MODEL=deepseek-chat ./scripts/ttv-check.sh  # 指定推理用的模型名
# 退出码：0=全通过；1=硬步骤失败（健康/登录/建密钥）；2=推理步骤未打通（管道通但无可用上游）
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
USERNAME="${TTV_USERNAME:-admin}"
PASSWORD="${TTV_PASSWORD:-ChangeMeOnFirstStartup123456}"
MODEL="${TTV_MODEL:-}"
CURL="${CURL:-curl}"
FAIL=0
START=$(date +%s%3N 2>/dev/null || python -c 'import time;print(int(time.time()*1000))')

step() { printf '\n[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
ms() { echo $(( $(date +%s%3N 2>/dev/null || python -c 'import time;print(int(time.time()*1000))') - $1 )); }
ok()   { printf '  \033[32m✓\033[0m %s（%sms）\n' "$1" "$2"; }
warn() { printf '  \033[33m△\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$1"; FAIL=1; }

now() { date +%s%3N 2>/dev/null || python -c 'import time;print(int(time.time()*1000))'; }

# ---------- 步骤 1：网关健康 ----------
step "1/4 网关健康检查"
T=$(now)
HEALTH=$($CURL -s -m 10 -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health") || HEALTH="000"
D=$(ms $T)
if [ "$HEALTH" = "200" ]; then
  ok "网关健康 $BASE_URL/actuator/health" "$D"
else
  bad "健康检查失败（HTTP $HEALTH）——确认服务已启动且 BASE_URL 正确"; exit 1
fi

# ---------- 步骤 2：登录获取 JWT ----------
step "2/4 管理员登录（$USERNAME）"
T=$(now)
LOGIN_JSON=$($CURL -s -m 10 -X POST "$BASE_URL/api/auth/jwt/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")
D=$(ms $T)
TOKEN=$(printf '%s' "$LOGIN_JSON" | python -c 'import sys,json;d=json.load(sys.stdin);print(d["data"]["token"])' 2>/dev/null)
if [ -n "${TOKEN:-}" ] && [ "$TOKEN" != "None" ]; then
  ok "登录成功，JWT 已获取" "$D"
else
  bad "登录失败——检查用户名/密码（prod 模式需先设置 INITIAL_ADMIN_PASSWORD）"; exit 1
fi

# ---------- 步骤 3：创建第一把 API Key ----------
step "3/4 创建 API 密钥"
T=$(now)
KEY_JSON=$($CURL -s -m 10 -X POST "$BASE_URL/api/auth/api-keys" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"description":"ttv-check 首把密钥（脚本自动创建）","enabled":true}')
D=$(ms $T)
APIKEY=$(printf '%s' "$KEY_JSON" | python -c 'import sys,json;d=json.load(sys.stdin);print(d["data"]["keyValue"])' 2>/dev/null)
if [ -n "${APIKEY:-}" ] && [ "$APIKEY" != "None" ]; then
  ok "密钥已签发（${APIKEY:0:8}****，完整值仅在创建时返回）" "$D"
else
  bad "密钥创建失败：$(printf '%s' "$KEY_JSON" | head -c 200)"; exit 1
fi

# ---------- 步骤 4：第一句对话 ----------
step "4/4 推理调用（${MODEL:-任意已配置模型}）"
T=$(now)
CHAT_JSON=$($CURL -s -m 60 -w '\n%{http_code}' -X POST "$BASE_URL/v1/chat/completions" \
  -H "X-API-Key: $APIKEY" -H 'Content-Type: application/json' \
  -d "{\"model\":\"${MODEL:-default}\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":8}")
BODY=$(printf '%s' "$CHAT_JSON" | sed '$d')
HTTP=$(printf '%s' "$CHAT_JSON" | tail -n1)
D=$(ms $T)
CHOICES=$(printf '%s' "$BODY" | python -c 'import sys,json;d=json.load(sys.stdin);print(len(d.get("choices",[])))' 2>/dev/null)
if [ "$HTTP" = "200" ] && [ "${CHOICES:-0}" != "0" ]; then
  ok "推理成功，拿到回复（管道全通）" "$D"
elif [ -n "$HTTP" ] && [ "$HTTP" != "000" ] && printf '%s' "$BODY" | head -c 400 | grep -qi 'error\|code'; then
  warn "网关管道已通（HTTP $HTTP），但无可用上游/模型：接入厂商后重跑第 4 步"
  printf '  网关响应摘要：%s\n' "$(printf '%s' "$BODY" | head -c 200)"
  FAIL=2
else
  bad "推理请求异常（HTTP $HTTP）"; FAIL=2
fi

# ---------- 汇总 ----------
TOTAL=$(( $(now) - START ))
printf '\n========== TTV 结果 ==========\n'
if [ "$FAIL" = "0" ]; then
  printf '\033[32m全链路通过\033[0m，总耗时 %sms（目标 ≤ 600000ms / 10 分钟）\n' "$TOTAL"
  exit 0
elif [ "$FAIL" = "2" ]; then
  printf '\033[33m部分通过\033[0m（健康/登录/密钥均通），总耗时 %sms；接入上游厂商后重跑即可全绿\n' "$TOTAL"
  exit 2
else
  printf '\033[31m存在硬失败\033[0m，请先解决标 ✗ 的步骤\n'
  exit 1
fi
