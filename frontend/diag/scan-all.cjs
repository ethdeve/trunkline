/* 全路由视觉规顺扫描：逐页测量 docScrollWidth/nav 可见性/溢出肇事者 + 截图
 * 视图 1440×900；/api/** 通用 mock（空数据），页面骨架照常渲染
 */
const { chromium } = require('playwright-core')
const fs = require('fs')

const BASE = 'http://localhost:3102'
const OUT = 'D:/wdx/wjy/jairouter-console/diag-pages'
fs.mkdirSync(OUT, { recursive: true })

const ROUTES = [
  { path: '/overview', tabs: [] },
  { path: '/quick-start', tabs: [] },
  { path: '/models?tab=instances', tabs: [] },
  { path: '/models?tab=services', tabs: [] },
  { path: '/models?tab=pools', tabs: [] },
  { path: '/models?tab=adapters', tabs: [] },
  { path: '/playground', tabs: ['对话', '向量', '重排', '语音', '图像'] },
  { path: '/keys', tabs: [] },
  { path: '/observe', tabs: ['Token 用量', '慢查询', '异常', '异常列表', '异常统计', '配额用量', '熔断', '熔断监控', '熔断历史', '限流'] },
  { path: '/tracing', tabs: ['追踪查询'] },
  { path: '/strategy', tabs: ['负载均衡', '熔断阈值'] },
  { path: '/quota', tabs: [] },
  { path: '/settings', tabs: ['权限', '审计日志', '配置版本', '数据持久化'] }
]

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

  await page.route(url => url.pathname.startsWith('/api/'), route => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, data: { list: [], total: 0 }, message: 'ok', timestamp: '2026-10-16 00:00:00' })
    })
  })

  const results = []

  async function measure(tag, shotName) {
    const r = await page.evaluate(() => {
      const vw = document.documentElement.clientWidth
      const out = {
        url: location.pathname + location.search,
        title: document.title,
        docScrollWidth: document.documentElement.scrollWidth,
        navVisible: !!document.querySelector('.console-nav')?.getClientRects().length,
        navLeft: (() => { const n = document.querySelector('.console-nav'); return n ? Math.round(n.getBoundingClientRect().left) : null })(),
        contentOverflowY: (() => { const c = document.querySelector('.console-content'); return c ? c.scrollHeight > c.clientHeight : null })(),
        offenders: []
      }
      for (const el of document.querySelectorAll('*')) {
        const b = el.getBoundingClientRect()
        if (b.width === 0 && b.height === 0) continue
        if (b.right > vw + 2 || b.left < -2) {
          // 内部滚动容器（overflow-x: auto/scroll）内的元素属正常滚动内容，不算页面级溢出
          let contained = false
          let p = el.parentElement
          while (p && !p.classList?.contains('console-content')) {
            const ox = getComputedStyle(p).overflowX
            if (ox === 'auto' || ox === 'scroll') { contained = true; break }
            p = p.parentElement
          }
          if (contained) continue
          const childWider = Array.from(el.children).some(c => {
            const cb = c.getBoundingClientRect()
            return cb.right > b.right + 2 || cb.left < b.left - 2
          })
          if (!childWider) out.offenders.push(`${el.tagName.toLowerCase()}.${(el.className || '').toString().split(' ')[0]}[${Math.round(b.left)}~${Math.round(b.right)}]`)
        }
      }
      out.offenders = out.offenders.slice(0, 4)
      out.overflow = out.docScrollWidth > vw + 2
      return out
    })
    r.tag = tag
    results.push(r)
    const flag = r.overflow ? 'OVERFLOW' : (r.navVisible ? 'ok' : 'NAV-HIDDEN')
    console.log(`${flag.padEnd(10)} ${tag.padEnd(28)} doc=${r.docScrollWidth} nav=${r.navLeft} ${r.offenders.join(' ')}`)
    await page.screenshot({ path: `${OUT}/${shotName}.png` })
    return r
  }

  for (const route of ROUTES) {
    const name = route.path.replace(/[\/?=]/g, '_').replace(/_+$/, '')
    await page.goto(BASE + route.path, { waitUntil: 'networkidle', timeout: 25000 }).catch(() => {})
    await page.waitForTimeout(2500)
    await measure(route.path, name)

    for (const tab of route.tabs) {
      const tabEl = page.locator('.el-tabs__item', { hasText: tab }).first()
      if (await tabEl.count()) {
        await tabEl.click().catch(() => {})
        await page.waitForTimeout(1800)
        await measure(`${route.path} » ${tab}`, `${name}_${tab.replace(/\s/g, '')}`)
      }
    }
  }

  console.log('\n===== SUMMARY =====')
  const bad = results.filter(r => r.overflow || !r.navVisible)
  if (bad.length === 0) console.log(`全部 ${results.length} 个视图：无横向溢出、左栏可见`)
  else bad.forEach(r => console.log(`BAD: ${r.tag} doc=${r.docScrollWidth} navVisible=${r.navVisible} ${r.offenders.join(' ')}`))

  await browser.close()
})().catch(e => { console.error('FATAL', e); process.exit(1) })
