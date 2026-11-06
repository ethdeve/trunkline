#!/usr/bin/env node
/**
 * 员工侧术语校验（verify-employee-terms）
 *
 * <p>对应 constants/employeeTerms.ts 头注释承诺的验收脚本：扫描员工可见页面
 * （views/my/**、views/playground/**）的模板可见文本，出现禁用词即失败。
 * 这是「员工页面不出现技术词」的可执行验收，不靠人眼。</p>
 *
 * <p>策略：粗粒度剥离 HTML 注释 / JS 块注释 / 行注释后做字面检索。
 * 不追求 AST 精确——宁可误报（人工确认后用行内 `verify-disable` 注释豁免），
 * 不可漏报。配置片段（<code> 示例里的 model 字段名等 API 契约字样）不属于
 * UI 文案，脚本不检查英文标识符，只检查中文禁用词与明确的英文技术词。</p>
 *
 * 用法：node scripts/verify-employee-terms.mjs
 * 退出码：0 = 通过；1 = 存在违规
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../src', import.meta.url))

/** 员工可见页面目录（与菜单「我的」组 + 测试场对应） */
const SCOPES = ['views/my', 'views/playground']

/**
 * 禁用词清单。
 *
 * 与 employeeTerms.ts 的 STAFF_ONLY_TERMS 保持同一口径：中文技术词直接禁；
 * 英文技术词（Temperature / Top P 等）若出现在 <code> 配置示例里属合法
 * （API 契约字段名），故只列「在 UI 文案中出现必属违规」的字面量。
 */
const BANNED = [
  '虚拟模型',
  '熔断',
  '适配器',
  '令牌桶',
  '令牌',
  'HALF_OPEN',
  'OPEN_HALF',
  'Base URL',
  'baseURL'
]

/** 行内豁免标记：该行有意使用技术词（如注释/文档链接），人工确认后加 */
const EXEMPT_MARK = 'verify-disable'

/** 收集目录下全部 .vue 文件（文案只存在于模板中，.ts 逻辑文件不扫） */
function collect(dir) {
  const out = []
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) out.push(...collect(p))
    else if (/\.vue$/.test(name)) out.push(p)
  }
  return out
}

/**
 * 取 .vue 的 <template> 区并剥离 HTML 注释。
 *
 * <script>/<style> 区是逻辑与样式，变量名出现 baseURL 属合法实现细节，
 * 不属于员工可见文案，整段跳过。
 */
function templateOf(source) {
  const m = source.match(/<template>[\s\S]*<\/template>/)
  const body = m ? m[0] : source
  return body.replace(/<!--[\s\S]*?-->/g, '')
}

let violations = 0
for (const scope of SCOPES) {
  const files = collect(join(root, scope))
  for (const file of files) {
    const rel = relative(root, file).replaceAll('\\', '/')
    const lines = templateOf(readFileSync(file, 'utf-8')).split('\n')
    lines.forEach((line, i) => {
      if (line.includes(EXEMPT_MARK)) return
      for (const term of BANNED) {
        if (line.includes(term)) {
          console.error(`${rel}:${i + 1}: 出现禁用词「${term}」`)
          console.error(`    ${line.trim().slice(0, 120)}`)
          violations++
        }
      }
    })
  }
}

if (violations > 0) {
  console.error(`\n❌ 员工侧术语校验失败：${violations} 处违规。`)
  console.error('   员工可见页面应使用人话（见 constants/employeeTerms.ts）。')
  console.error('   确属合法使用时，在该行尾加注释 verify-disable 并说明原因。')
  process.exit(1)
}
console.log('✅ 员工侧术语校验通过：views/my + views/playground 无禁用技术词。')
