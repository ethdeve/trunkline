/* Keys 页宽表验证：mock /auth/api-keys 返回宽行数据，
 * 验证表格根滚动容器生效（scrollWidth > clientWidth）+ 固定右列可达 + 截图
 */
const { chromium } = require('playwright-core')

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
    const path = route.request().url().replace(/^https?:\/\/[^/]+/, '')
    const fulfill = data => route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ success: true, data, message: 'ok', timestamp: '2026-10-16 00:00:00' }) })
    if (path.includes('/auth/api-keys')) {
      const keys = []
      for (let i = 1; i <= 3; i++) {
        keys.push({
          keyId: 'ak-' + String(i).repeat(24),
          description: '东湖高新集团数据中台智能体平台调用密钥 ' + i,
          permissions: ['ai:playground:use', 'monitoring:tokenusage:read', 'config:instances:read'],
          createdAt: '2026-09-0' + i + ' 10:24:36',
          createdBy: 'zhangsan@donghu-park.cn',
          expiresAt: '2027-09-0' + i + ' 10:24:36',
          remainingDays: 320 - i,
          totalRequests: 10240 * i,
          successfulRequests: 10200 * i,
          failedRequests: 40 * i,
          todayTokenUsage: 1250000 * i,
          quotaAlertTriggered: i === 2,
          enabled: true,
          expired: false
        })
      }
      return fulfill({
        items: keys,
        total: keys.length,
        enabledCount: 2,
        disabledCount: 1,
        expiredCount: 0,
        summary: { todayTotalRequests: 1024, todaySuccessfulRequests: 1000, todayFailedRequests: 24, todayTokenUsage: 2500000 }
      })
    }
    fulfill({ list: [], total: 0 })
  })

  await page.goto('http://localhost:3102/keys', { waitUntil: 'networkidle', timeout: 25000 }).catch(() => {})
  await page.waitForTimeout(3000)

  const r = await page.evaluate(() => {
    const root = document.querySelector('.table-wrapper .el-table')
    const fixed = document.querySelector('.el-table__fixed-right')
    const vw = document.documentElement.clientWidth
    return {
      viewport: vw,
      docScrollWidth: document.documentElement.scrollWidth,
      navLeft: Math.round(document.querySelector('.console-nav')?.getBoundingClientRect().left ?? -1),
      rootScrollWidth: root?.scrollWidth ?? null,
      rootClientWidth: root?.clientWidth ?? null,
      rootOverflowX: root ? getComputedStyle(root).overflowX : null,
      headerWrapOverflow: (() => { const w = document.querySelector('.table-wrapper .el-table__header-wrapper'); return w ? getComputedStyle(w).overflowX : null })(),
      fixedRightVisible: !!fixed?.getClientRects().length,
      fixedRightRect: (() => { if (!fixed) return null; const b = fixed.getBoundingClientRect(); return { left: Math.round(b.left), right: Math.round(b.right) } })(),
      rowsRendered: document.querySelectorAll('.table-wrapper .el-table__body tr').length
    }
  })
  console.log(JSON.stringify(r, null, 1))
  await page.screenshot({ path: 'D:/wdx/wjy/trunkline-console/diag-keys-data.png' })
  await browser.close()
})().catch(e => { console.error('FATAL', e); process.exit(1) })
