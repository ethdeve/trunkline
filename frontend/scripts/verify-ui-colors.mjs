#!/usr/bin/env node
/**
 * UI 颜色门禁（verify-ui-colors）
 *
 * <p>对应 docs/product/Trunkline-控制台说人话改造方案.md §六-补2 U1 的可执行验收：
 * views/components 内禁止出现 var() 之外的裸 hex 色值——裸色不随亮/暗主题切换，
 * 是暗色模式「漏白」的根因。颜色一律走 styles/tokens.css 的 --ja-* 令牌或
 * Element 的 --el-* 变量（回退值写在 var() 内属合法）。</p>
 *
 * <p>策略：按行剥离注释（双斜杠行注释、HTML 注释、斜杠星块注释）与 var(...) 段后检索 hex。
 * 宁可误报（人工确认后行内加 `verify-disable` 豁免），不可漏报。</p>
 *
 * 用法：node scripts/verify-ui-colors.mjs
 * 退出码：0 = 通过；1 = 存在违规
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../src', import.meta.url))
const SCOPES = ['views', 'components']

/** 文件级豁免：语法高亮主题 / echarts 画布色等整文件刻意用色，逐行标豁免不现实 */
const FILE_EXEMPTS = [
  'views/playground/components/common/MarkdownRenderer.vue',
  'views/playground/components/common/CodeBlock.vue',
  'views/playground/components/common/MessageBubble.vue',
  'views/_attic/'
]

/** 行内豁免标记（与 verify-employee-terms 同一约定） */
const EXEMPT_MARK = 'verify-disable'

const HEX_RE = /#[0-9a-fA-F]{3,8}\b/

function collect(dir) {
  const out = []
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) out.push(...collect(p))
    else if (/\.vue$/.test(name)) out.push(p)
  }
  return out
}

/** 剥离块注释、HTML 注释与行注释，再反复剥掉 var(...) 段（回退值合法） */
function stripLine(line) {
  let s = line.replace(/\/\*[\s\S]*?\*\//g, '')
  s = s.replace(/<!--[\s\S]*?-->/g, '')
  s = s.replace(/(^|[^:])\/\/.*$/, '$1')
  let prev
  do {
    prev = s
    s = s.replace(/var\([^()]*\)/g, 'var()')
  } while (s !== prev)
  return s
}

let violations = 0
for (const scope of SCOPES) {
  const files = collect(join(root, scope))
  for (const file of files) {
    const rel = relative(root, file).replaceAll('\\', '/')
    if (FILE_EXEMPTS.some((p) => rel.replaceAll('\\', '/').includes(p))) continue
    const lines = readFileSync(file, 'utf-8').split('\n')
    lines.forEach((line, i) => {
      if (line.includes(EXEMPT_MARK)) return
      const m = stripLine(line).match(HEX_RE)
      if (m) {
        console.error(`${rel}:${i + 1}: 出现 var() 之外的裸色值 ${m[0]}`)
        console.error(`    ${line.trim().slice(0, 120)}`)
        violations++
      }
    })
  }
}

if (violations > 0) {
  console.error(`\n❌ UI 颜色门禁失败：${violations} 处裸色值。`)
  console.error('   颜色请改用 var(--ja-*) 设计令牌或 var(--el-*) 主题变量（暗色自适应）。')
  console.error('   确属刻意用色（代码高亮/图表画布等），在该行加注释 verify-disable 并说明原因。')
  process.exit(1)
}
console.log('✅ UI 颜色门禁通过：views/components 无 var() 之外的裸色值。')
