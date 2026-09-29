import { expect, test, type Page } from '@playwright/test'

/**
 * 桌面大盘（M5-③）：桌面壳落地页（'/' 重定向，D-072）+ 全队指标卡 +
 * 雅虎同步卡（无成功批次占位）+ 两仓明细表 + 出荷待ち跳转。
 * 本 spec 时点（admin/arrival/auth 之后）夹具件已被 arrival 尾部作废清空、
 * 雅虎/Excel 导入尚未发生——数值为确定性的空态（0/—/无导入）。
 */
const E2E_PASSWORD = 'e2e-pass-123456'

test.beforeEach(() => {
  test.skip(test.info().project.name !== 'desktop-chromium', '仅 desktop-chromium 项目执行')
})

async function login(page: Page, username: string): Promise<void> {
  await page.goto('/login')
  await page.fill('#login-username', username)
  await page.fill('#login-password', E2E_PASSWORD)
  await page.getByRole('button', { name: 'ログイン' }).click()
  await expect(page.locator('.dashboard-view')).toBeVisible()
}

test.describe('dashboard (desktop-chromium)', () => {
  test('root redirects to /dashboard and renders the fleet grid, yahoo card, and warehouse table on an empty install', async ({ page }) => {
    await login(page, 'viewer')
    await expect(page).toHaveURL(/\/dashboard$/)

    // 根路径显式回访仍落大盘（守卫重定向，非一次性跳转）
    await page.goto('/')
    await expect(page).toHaveURL(/\/dashboard$/)

    // 全队指标卡九枚：空库计数 0、货值 ￥0、无在库样本库龄「—」
    const fleetCard = page.locator('.dashboard-card').filter({ hasText: '全体サマリー' })
    await expect(fleetCard).toBeVisible()
    await expect(fleetCard.locator('.dashboard-stat')).toHaveCount(9)
    await expect(fleetCard.locator('.dashboard-stat.is-inStock dd')).toHaveText('0')
    await expect(fleetCard.locator('.dashboard-stat.is-inTransit dd')).toHaveText('0')
    await expect(fleetCard.locator('.dashboard-stat.is-shipped dd')).toHaveText('0')
    await expect(fleetCard.locator('.dashboard-stat.is-monthInbound dd')).toHaveText('0')
    await expect(fleetCard.locator('.dashboard-stat.is-monthOutbound dd')).toHaveText('0')
    await expect(fleetCard.locator('.dashboard-stat.is-slowWarn dd')).toHaveText('0')
    await expect(fleetCard.locator('.dashboard-stat.is-slowRed dd')).toHaveText('0')
    await expect(fleetCard.locator('.dashboard-stat.is-stockValue dd')).toHaveText('￥0')
    await expect(fleetCard.locator('.dashboard-stat.is-avgStockAgeDays dd')).toHaveText('—')

    // 雅虎卡：三计数 0 + 无成功批次占位
    const yahooCard = page.locator('.dashboard-card').filter({ hasText: 'ヤフー連携' })
    await expect(yahooCard.locator('.dashboard-stat')).toHaveCount(3)
    await expect(yahooCard.locator('.dashboard-stat.is-soldNotShipped dd')).toHaveText('0')
    await expect(yahooCard.locator('.dashboard-last-import'))
      .toHaveText('受注ファイルのインポートはまだありません')

    // 两仓明细表：名古屋/福岡 各一行
    const rows = page.locator('.dashboard-table tbody tr')
    await expect(rows).toHaveCount(2)
    await expect(rows.nth(0).locator('th')).toHaveText('名古屋倉庫')
    await expect(rows.nth(1).locator('th')).toHaveText('福岡倉庫')
  })

  test('yahoo card links navigate to pending shipments and back to the yahoo view', async ({ page }) => {
    await login(page, 'viewer')

    await page.locator('.dashboard-link-btn').filter({ hasText: '出荷待ちへ' }).click()
    await expect(page).toHaveURL(/\/pending-shipments$/)

    // goBack 重挂大盘组件（RouterView 无 keep-alive）→ 两路 stats 重拉重渲染；
    // 等数据落定再点卡片按钮，防点击落进重渲染瞬间导航静默丢失（M5-④ 同族病灶）
    const statsSettled = Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/stats/dashboard') && r.request().method() === 'GET'),
      page.waitForResponse((r) => r.url().includes('/api/stats/warehouses') && r.request().method() === 'GET'),
    ])
    await page.goBack()
    await expect(page.locator('.dashboard-view')).toBeVisible()
    await statsSettled
    await page.locator('.dashboard-link-btn').filter({ hasText: 'ヤフー連携へ' }).click()
    await expect(page).toHaveURL(/\/yahoo$/)
  })
})
