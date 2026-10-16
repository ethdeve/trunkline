/* 三表修复验证 v2：修正路由优先级、/api 前缀、lazy tab 逐层点击、表格定位 */
const { chromium } = require('playwright-core')
const fs = require('fs')

const BASE = 'http://localhost:3102'
const OUT = 'D:/wdx/wjy/jairouter-console/diag-pages'
fs.mkdirSync(OUT, { recursive: true })

const envelope = data => ({ success: true, message: 'ok', data, timestamp: '2026-10-16 00:00:00' })

const EXCEPTIONS = Array.from({ length: 8 }, (_, i) => ({
  eventId: `exc-20261015-9f8e7d6c${i}`,
  exceptionType: 'ReadTimeoutException',
  sanitizedMessage: 'Connection to https://api.openai.com/v1/chat/completions timed out after 30000ms while awaiting response headers',
  errorCode: 'READ_TIMEOUT',
  errorCategory: 'TIMEOUT',
  traceId: `4bf92f3577b34da6a3ce929d0e0e4736-${i}`,
  clientIp: '192.168.1.100',
  serviceType: 'chat-completions',
  modelName: 'gpt-4o-mini-2024-07-18',
  provider: 'openai',
  responseTimeMs: 30000,
  occurredAt: '2026-10-15 14:32:07'
}))

const QUOTA = Array.from({ length: 8 }, (_, i) => ({
  dimensions: {
    tenantId: 'tenant-donghu-gaoxin',
    apiKeyId: `sk-abc123def456ghi789jkl${i}`,
    userId: `user-0042-zhangsan${i}`,
    serviceType: 'chat-completions',
    model: 'gpt-4o-mini-2024-07-18'
  },
  window: '1h',
  windowStart: '2026-10-15 14:00:00',
  requestCount: 15234 + i * 137,
  tokenCount: 2841503 + i * 9721
}))

const CIRCUIT_HISTORY = {
  'inst-openai-primary-01': Array.from({ length: 6 }, (_, i) => ({
    timestamp: new Date(Date.UTC(2026, 9, 15, 14, 30 + i)).toISOString(),
    instanceId: 'inst-openai-primary-01',
    instanceName: 'OpenAI 主实例-华东1',
    serviceType: 'chat-completions',
    eventType: i % 2 ? 'CIRCUIT_CLOSE' : 'CIRCUIT_OPEN',
    previousState: i % 2 ? 'OPEN' : 'CLOSED',
    currentState: i % 2 ? 'CLOSED' : 'OPEN',
    failureCount: 5 + i,
    successCount: i * 2,
    triggerReason: '连续 5 次调用失败（ReadTimeout），失败率超过阈值 60%，触发熔断'
  })),
  'inst-azure-backup-02': Array.from({ length: 4 }, (_, i) => ({
    timestamp: new Date(Date.UTC(2026, 9, 15, 13, 10 + i)).toISOString(),
    instanceId: 'inst-azure-backup-02',
    instanceName: 'Azure 备实例',
    serviceType: 'embeddings',
    eventType: 'CIRCUIT_HALF_OPEN',
    previousState: 'OPEN',
    currentState: 'HALF_OPEN',
    failureCount: 3,
    successCount: 1,
    triggerReason: '熔断冷却期结束，进入半开状态试探'
  }))
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

  // 注意：Playwright 路由后注册的优先——先注册 catch-all，再注册精确 mock
  await page.route(url => {
    const p = url.pathname
    return (p.startsWith('/api/') || p.startsWith('/v1/')) && !p.startsWith('/src/')
  }, route => {
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(envelope({ list: [], total: 0, items: [], content: [] })) })
  })

  await page.route(url => {
    const p = url.pathname
    return p === '/api/exceptions' || p === '/api/monitoring/quota/usage' ||
           p.startsWith('/api/v1/circuit-breaker-monitor') ||
           p.startsWith('/api/config/instance/circuit-breaker')
  }, route => {
    const p = route.request().url().replace(/^https?:\/\/[^/]+/, '').split('?')[0]
    const raw = obj => ({ status: 200, contentType: 'application/json', body: JSON.stringify(obj) })
    if (p === '/api/exceptions') {
      return route.fulfill(raw(envelope({
        content: EXCEPTIONS, page: 0, size: 20, totalElements: EXCEPTIONS.length,
        totalPages: 1, first: true, last: true, hasNext: false, hasPrevious: false
      })))
    }
    if (p === '/api/monitoring/quota/usage') {
      return route.fulfill(raw(envelope(QUOTA)))
    }
    // circuit 的 history/status 返回裸数据（非信封）
    if (p.endsWith('/history')) return route.fulfill(raw(CIRCUIT_HISTORY))
    if (p.endsWith('/status')) return route.fulfill(raw({ monitoringEnabled: true, sampleRate: 0.5, historySize: 200, totalEvents: 128, wsConnected: true }))
    if (p.endsWith('/states')) return route.fulfill(raw(envelope({ 'inst-openai-primary-01-https://api.openai.com': 'CLOSED', 'inst-azure-backup-02-https://azure.example.com': 'OPEN' })))
    route.fulfill(raw(envelope({ list: [], total: 0 })))
  })

  async function clickTab(text) {
    const el = page.locator('.el-tabs__item', { hasText: text }).first()
    if (await el.count()) { await el.click().catch(() => {}); await page.waitForTimeout(1200); return true }
    console.log(`   [warn] tab 不存在: ${text}`)
    return false
  }

  async function deepMeasure(tag, shotName) {
    await page.waitForTimeout(2200)
    const r = await page.evaluate(() => {
      const vw = document.documentElement.clientWidth
      const nav = document.querySelector('.console-nav')
      // 选「可见的、行数最多」的表（排除 display:none 的隐藏 pane）
      let table = null, best = -1
      document.querySelectorAll('.el-table').forEach(t => {
        if (!t.getClientRects().length) return
        const n = t.querySelectorAll('.el-table__body tr').length
        if (n > best) { best = n; table = t }
      })
      const out = {
        docScrollWidth: document.documentElement.scrollWidth,
        pageOverflow: document.documentElement.scrollWidth > vw + 2,
        navVisible: !!nav?.getClientRects().length,
        navLeft: nav ? Math.round(nav.getBoundingClientRect().left) : null,
        tableFound: !!table,
        rows: best,
        scroller: null,
        tableW: null,
        lastColRight: null,
        fixedRight: null,
        verticalScroll: null,
        sync: null
      }
      if (!table) return out
      out.tableW = Math.round(table.getBoundingClientRect().width)
      // 滚动端口 = 表格根本身（.table-scroll-x 模式下根 overflow:auto）
      out.rootScroll = { clientW: table.clientWidth, scrollW: table.scrollWidth, canScroll: table.scrollWidth > table.clientWidth + 4 }
      const cells = table.querySelectorAll('.el-table__body tr:last-child .el-table__cell')
      if (cells.length) out.lastColRight = Math.round(cells[cells.length - 1].getBoundingClientRect().right)
      const fr = table.querySelector('.el-table__fixed-right')
      if (fr) out.fixedRight = Math.round(fr.getBoundingClientRect().right)
      const wrap = table.querySelector('.el-table__body-wrapper .el-scrollbar__wrap') || table.querySelector('.el-table__body-wrapper')
      if (wrap) out.verticalScroll = { scrollH: wrap.scrollHeight, clientH: wrap.clientHeight, canScroll: wrap.scrollHeight > wrap.clientHeight + 4 }
      // 横向滚动同步性：滚表格根，看表头/表体是否一起动
      if (out.rootScroll.canScroll) {
        const hL = () => { const h = table.querySelector('.el-table__header'); return h ? Math.round(h.getBoundingClientRect().left) : null }
        const bL = () => { const b = table.querySelector('.el-table__body'); return b ? Math.round(b.getBoundingClientRect().left) : null }
        const before = { header: hL(), body: bL() }
        table.scrollLeft = 400
        const after = { header: hL(), body: bL() }
        out.sync = { before, after, aligned: after.header === after.body, moved: before.header !== after.header }
        table.scrollLeft = 0
      }
      return out
    })
    r.tag = tag
    console.log(`\n### ${tag}`)
    console.log('   ', JSON.stringify(r))
    await page.screenshot({ path: `${OUT}/${shotName}.png` })
    return r
  }

  await page.goto(`${BASE}/observe`, { waitUntil: 'networkidle', timeout: 25000 }).catch(() => {})
  await page.waitForTimeout(2000)

  // 1. 异常 » 异常列表（外层 lazy，先点外层）
  await clickTab('异常')
  await clickTab('异常列表')
  const r1 = await deepMeasure('/observe » 异常 » 异常列表', 'fix_exceptions')
  await page.evaluate(() => {
    const t = [...document.querySelectorAll('.table-scroll-x .el-table')].find(e => e.getClientRects().length)
    if (t && t.scrollWidth > t.clientWidth) t.scrollLeft = 400
    const w = document.querySelector('.el-table__body-wrapper .el-scrollbar__wrap')
    if (w) w.scrollTop = 200
  })
  await page.waitForTimeout(600)
  await page.screenshot({ path: `${OUT}/fix_exceptions_scrolled.png` })
  {
    const box = await page.evaluate(() => {
      const t = [...document.querySelectorAll('.table-scroll-x .el-table')].find(e => e.getClientRects().length)
      const th = t.querySelector('.el-table__header')
      const fr = t.querySelector('.el-table__fixed-right')
      const b = (fr || th).getBoundingClientRect()
      return { x: Math.round(b.right - 320), y: Math.round(b.top), width: 320, height: 50 }
    })
    await page.screenshot({ path: `${OUT}/fix_exceptions_header_zoom.png`, clip: box, fullPage: true })
  }

  // 2. 配额用量
  await clickTab('配额用量')
  const r2 = await deepMeasure('/observe » 配额用量', 'fix_quota')
  await page.evaluate(() => { const t = [...document.querySelectorAll('.table-scroll-x .el-table')].find(e => e.getClientRects().length); if (t && t.scrollWidth > t.clientWidth) t.scrollLeft = 400 })
  await page.waitForTimeout(600)
  await page.screenshot({ path: `${OUT}/fix_quota_scrolled.png` })
  {
    const box = await page.evaluate(() => {
      const t = [...document.querySelectorAll('.table-scroll-x .el-table')].find(e => e.getClientRects().length)
      const b = t.getBoundingClientRect()
      return { x: Math.round(b.right - 340), y: Math.round(b.top), width: 340, height: 50 }
    })
    console.log('QUOTA-ZOOM-BOX:', JSON.stringify(box), 'scrollY=', await page.evaluate(() => scrollY))
    await page.screenshot({ path: `${OUT}/fix_quota_header_zoom.png`, clip: box, fullPage: true })
  }

  // 3. 熔断 » 熔断监控
  await clickTab('熔断')
  await clickTab('熔断监控')
  const r3 = await deepMeasure('/observe » 熔断 » 熔断监控', 'fix_circuit')

  console.log('\n===== VERDICT =====')
  for (const r of [r1, r2, r3]) {
    const ok = !r.pageOverflow && r.navVisible && r.rows > 0 && (r.sync ? r.sync.aligned : true)
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${r.tag}  rows=${r.rows} tableW=${r.tableW} rootScroll=${r.rootScroll ? r.rootScroll.clientW + '/' + r.rootScroll.scrollW : 'n/a'} sync=${r.sync ? r.sync.aligned : 'n/a'} vScroll=${r.verticalScroll ? r.verticalScroll.canScroll : 'n/a'}`)
  }

  await browser.close()
})().catch(e => { console.error('FATAL', e); process.exit(1) })
