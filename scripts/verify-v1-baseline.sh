#!/usr/bin/env bash
# verify-v1-baseline.sh —— Trunkline 单一基线（V1__baseline.sql）防带病分叉护栏
#
# 用途：在构建期校验 h2 / postgres 两份 V1__baseline.sql 的 SHA256 与
#       src/main/resources/db/migration/v1-baseline.sha256 一致。
#       任何不匹配都视为「V1 被改动」，按私有化交付约束必须以增量迁移替代，
#       否则客户侧 Flyway checksum 失配、启动失败。
#
# 退出码：0 = 一致；1 = 不一致 / 文件缺失 / 工具缺失。
#
# 由 OSS 仓 pom.xml 的 maven-antrun-plugin（validate 阶段）调用；
# 也可手动运行：bash scripts/verify-v1-baseline.sh [migration_dir]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIG_DIR="${1:-$SCRIPT_DIR/src/main/resources/db/migration}"
MANIFEST="$MIG_DIR/v1-baseline.sha256"

if [[ ! -f "$MANIFEST" ]]; then
  echo "ERROR: 基线清单缺失: $MANIFEST" >&2
  exit 1
fi

if ! command -v sha256sum >/dev/null 2>&1; then
  echo "ERROR: 需要 sha256sum（Git Bash / Linux / macOS 自带）" >&2
  exit 1
fi

rc=0
while read -r expected_hash rel_path _; do
  # 跳过注释与空行
  [[ -z "$expected_hash" || "$expected_hash" == \#* ]] && continue
  target="$MIG_DIR/$rel_path"
  if [[ ! -f "$target" ]]; then
    echo "FAIL: 基线文件缺失: $rel_path" >&2
    rc=1
    continue
  fi
  actual_hash="$(sha256sum "$target" | awk '{print $1}')"
  if [[ "$actual_hash" != "$expected_hash" ]]; then
    echo "FAIL: $rel_path 哈希失配（V1 被改动）" >&2
    echo "  期望: $expected_hash" >&2
    echo "  实际: $actual_hash" >&2
    rc=1
  fi
done < "$MANIFEST"

if [[ $rc -ne 0 ]]; then
  cat >&2 <<'EOF'

================================================================================
V1 基线校验未通过。

单一基线（V1__baseline.sql）是 Flyway 空库的唯一起点。一旦有客户部署，
改动 V1 会导致 Flyway checksum 失配、应用启动失败。

正确处理方式（二选一）：
  1) 本仓尚未发布、确认无客户部署：同步更新 v1-baseline.sha256 并经评审：
       cd src/main/resources/db/migration
       sha256sum h2/V1__baseline.sql postgres/V1__baseline.sql > v1-baseline.sha256
  2) 本仓已发布 / 有存量客户：严禁再改 V1，schema 变更改为新增
     V4__<desc>.sql 增量迁移脚本（不回改 V1），并删除本护栏的报错。

绝不允许在未经上述任一步骤的情况下直接修改 V1__baseline.sql。
================================================================================
EOF
  exit 1
fi

echo "OK: V1 基线哈希一致（h2 / postgres 两份 V1__baseline.sql 未被改动）"
