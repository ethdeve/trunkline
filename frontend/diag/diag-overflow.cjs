/* 诊断 v3：mock 全部 /api/** 返回 200 + 真实感数据，避免 401 重定向，
 * 然后测量 /models?tab=instances 的横向溢出肇事者。
 * mock 数据刻意用长 URL / 长实例名，逼近用户真实环境的数据宽度。
 */
const { chromium } = require('playwright-core')

const BASE = 'http://localhost:3101'

const MOCK = {
  '/api/config/type/services': ['chat', 'embedding', 'rerank'],
  '/api/config/adapter': [
    { id: 'openai', name: 'OpenAI 兼容适配器' },
    { id: 'dashscope', name: 'DashScope 适配器' }
  ]
}

function instanceList() {
  const rows = []
  const names = ['openai-main', 'azure-gpt4o-backup', 'deepseek-v3-official', 'qwen-max-long', 'kimi-k2-thinking']
  const urls = [
    'https://api.openai.com/v1/chat/completions',
    'https://eastus.api.cognitive.microsoft.com/openai/deployments/gpt-4o/chat/completions',
    'https://api.deepseek.com/v1/chat/completions',
    'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions',
    'https://api.moonshot.cn/v1/chat/completions'
  ]
  for (let i = 0; i < names.length; i++) {
    rows.push({
      id: i + 1,
      instanceId: String(i + 1),
      name: names[i],
      baseUrl: urls[i],
      path: '/v1/chat/completions',
      weight: 100,
      status: 'active',
      adapter: i % 2 === 0 ? 'openai' : '',
      headers: i % 3 === 0 ? { 'X-Tenant': 'donghu' } : null,
      tags: i % 2 === 1 ? { env: 'prod' } : null,
      health: i !== 2
    })
  }
  return rows
}

;(async () => {
  const browser = await chromium.launch({
    executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage']
  })
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })

  await page.addInitScript(() => {
    const payload = btoa(JSON.stringify({ exp: 9999999999, sub: 'diag', roles: ['ADMIN'], permissions: [] }))
    localStorage.setItem('admin_token', 'x.' + payload + '.y')
  })

  // 拦截 /api/**：按路径给 mock，其余给通用空成功（函数匹配：只要 /api/ 且非 /src/api/ 模块）
  const intercepted = []
  await page.route(url => {
    if (!url.pathname.startsWith('/api/')) return false
    intercepted.push(url.pathname + url.search)
    return true
  }, route => {
    const url = route.request().url()
    const path = url.replace(/^https?:\/\/[^/]+/, '')
    const fulfill = (data) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, data, message: 'ok', timestamp: '2026-10-16 00:00:00' })
    })
    if (path.includes('/config/type/services/batch')) return fulfill({ chat: {}, embedding: {}, rerank: {} })
    if (/\/config\/instance\/[^/]+$/.test(path)) return fulfill(instanceList())
    if (path.includes('/config/instance/')) return fulfill(instanceList())
    for (const [k, v] of Object.entries(MOCK)) if (path.startsWith(k)) return fulfill(v)
    if (path.includes('/monitoring/overview')) return fulfill({ totalCalls: 0, successRate: 0, avgLatency: 0, totalTokens: 0, activeInstances: 0, unhealthyInstances: 0 })
    if (path.includes('/call-history/dashboard')) return fulfill({ recentCalls: [], stats: {} })
    if (path.includes('/config/type')) return fulfill({ chat: {}, embedding: {}, rerank: {} })
    fulfill([])
  })

  const logs = []
  page.on('console', m => logs.push(`[${m.type()}] ${m.text().slice(0, 200)}`))
  page.on('pageerror', e => logs.push(`[pageerror] ${String(e).slice(0, 300)}`))
  page.on('requestfailed', r => logs.push(`[reqfail] ${r.url().slice(0, 120)} ${r.failure()?.errorText ?? ''}`))

  async function measure(tag) {
    const r = await page.evaluate(() => {
      const vw = document.documentElement.clientWidth
      const doc = document.documentElement
      const out = {
        url: location.href,
        title: document.title,
        viewport: vw,
        docScrollWidth: doc.scrollWidth,
        shellScrollWidth: document.querySelector('.console-shell')?.scrollWidth ?? null,
        shellClientWidth: document.querySelector('.console-shell')?.clientWidth ?? null,
        contentScrollWidth: document.querySelector('.console-content')?.scrollWidth ?? null,
        contentClientWidth: document.querySelector('.console-content')?.clientWidth ?? null,
        mainScrollWidth: document.querySelector('.console-main')?.scrollWidth ?? null,
        navVisible: !!document.querySelector('.console-nav')?.getClientRects().length,
        navCount: document.querySelectorAll('.console-nav').length,
        navRect: (() => { const n = document.querySelector('.console-nav'); if (!n) return null; const r = n.getBoundingClientRect(); return { left: Math.round(r.left), right: Math.round(r.right), width: Math.round(r.width) } })(),
        tableRect: (() => { const t = document.querySelector('.instance-table'); if (!t) return null; const r = t.getBoundingClientRect(); return { left: Math.round(r.left), right: Math.round(r.right), width: Math.round(r.width) } })(),
        tableWrapScroll: (() => { const w = document.querySelector('.el-table__inner-wrapper'); if (!w) return null; return { scrollWidth: w.scrollWidth, clientWidth: w.clientWidth, overflowX: getComputedStyle(w).overflowX } })(),
        offenders: []
      }
      const all = Array.from(document.querySelectorAll('*'))
      for (const el of all) {
        const r = el.getBoundingClientRect()
        if (r.width === 0 && r.height === 0) continue
        if (r.right > vw + 2 || r.left < -2) {
          const childWider = Array.from(el.children).some(c => {
            const cr = c.getBoundingClientRect()
            return cr.right > r.right + 2 || cr.left < r.left - 2
          })
          if (!childWider) {
            out.offenders.push({
              tag: el.tagName.toLowerCase(),
              cls: (el.className && el.className.toString ? el.className.toString() : '').slice(0, 80),
              left: Math.round(r.left), right: Math.round(r.right), width: Math.round(r.width)
            })
          }
        }
      }
      out.offenders = out.offenders.slice(0, 20)
      return out
    })
    console.log(`\n===== ${tag} =====`)
    console.log(JSON.stringify(r, null, 1))
    return r
  }

  await page.goto(`${BASE}/models?tab=instances`, { waitUntil: 'networkidle', timeout: 30000 }).catch(e => logs.push('goto: ' + e.message.slice(0, 80)))
  await page.waitForTimeout(4000)
  console.log('\n===== BOOT LOGS (A 前) =====')
  console.log(logs.slice(0, 30).join('\n') || '(none)')
  console.log('\n===== BODY HTML (前600字) =====')
  console.log(await page.evaluate(() => document.body.innerHTML.slice(0, 600)))
  await measure('A. mock 后直接加载 /models?tab=instances')
  await page.screenshot({ path: 'D:/wdx/wjy/jairouter-console/diag-a-direct.png' })

  // 真实流程：总览 → 点左栏「上游模型」
  await page.goto(`${BASE}/overview`, { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {})
  await page.waitForTimeout(1500)
  await page.locator('.nav-menu .el-menu-item', { hasText: '上游模型' }).first().click().catch(e => logs.push('click nav: ' + e.message.slice(0, 100)))
  await page.waitForTimeout(3500)
  await measure('B. 点击左栏「上游模型」后')
  await page.screenshot({ path: 'D:/wdx/wjy/jairouter-console/diag-b-navclick.png' })

  console.log('\n===== INTERCEPTED API URLS =====')
  console.log(intercepted.slice(0, 25).join('\n') || '(none)')
  console.log('\n===== PAGE ERRORS =====')
  console.log(logs.slice(0, 10).join('\n') || '(none)')
  await browser.close()
})().catch(e => { console.error('FATAL', e); process.exit(1) })
