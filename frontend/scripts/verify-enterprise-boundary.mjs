/**
 * CI 守卫：OSS 控制台不得重新混入企业付费业务代码（授权协议 1.1 / A2 分离）。
 *
 * 判定口径（open-core）：OSS 允许保留「机制」——探测契约、Noop、loader、承载路由、
 * 灰态；但禁止出现「企业业务视图/业务端点客户端」。命中即非零退出。
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = fileURLToPath(new URL('../src', import.meta.url))

/** 允许出现企业端点的机制文件（探测/租户机制/加载器）。 */
const ALLOWED = new Set([
  'api/enterprise.ts',
  'api/tenant.ts',
  'extension/loader.ts',
  'extension/types.ts'
])

/** 企业业务端点特征串——出现在非允许文件即视为回流。 */
const FORBIDDEN = [
  '/v1/enterprise/license',
  '/v1/enterprise/reconciliation',
  '/v1/enterprise/agent-tools',
  '/v1/enterprise/audit-chain',
  '/v1/enterprise/archive',
  '/v1/enterprise/dlp',
  '/v1/enterprise/kms',
  '/v1/enterprise/moderation',
  '/v1/enterprise/security-dashboard',
  '/v1/enterprise/alert-channels',
  '/config/prompt-templates',
  '/security/group-roles',
  '/auth/api-key-requests'
]

function walk(dir, base = '') {
  const out = []
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    const rel = base ? `${base}/${name}` : name
    if (statSync(full).isDirectory()) out.push(...walk(full, rel))
    else if (/\.(ts|vue)$/.test(name)) out.push(rel)
  }
  return out
}

const violations = []
for (const rel of walk(SRC)) {
  if (ALLOWED.has(rel)) continue
  const content = readFileSync(join(SRC, rel), 'utf8')
  for (const needle of FORBIDDEN) {
    if (content.includes(needle)) {
      violations.push(`${rel}: ${needle}`)
    }
  }
}

if (violations.length) {
  console.error('[enterprise-guard] 企业业务代码回流 OSS，违反授权协议 1.1：')
  for (const v of violations) console.error('  - ' + v)
  process.exit(1)
}
console.log('[enterprise-guard] OK — OSS 无企业业务代码残留')
