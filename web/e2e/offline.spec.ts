import { expect, test, type APIResponse, type Page } from '@playwright/test'
import { TEST_JPEG } from './fixtures'

/**
 * 弱网/离线自动化（docs/01 E2E 节：context.setOffline 与 route abort 两形态）：
 * ① 盘点扫码离线小队列（M6-②）：断网照记（离线卡+计数含队列+close 拦截）→
 *    恢复 online 自动回放（服务端同单同件幂等）→ 计数切回服务端口径、close 解锁，
 *    终态以 API 断言（scannedCount=1，重复回放不重复计数）；
 * ② 图片上传退避重试（M2-5 回归，docs/01 7.5）：/api/images 被 abort →
 *    角标停在送信中（退避等待）→ online 事件立即重试 → 出清且服务端恰 1 张
 *    （clientUuid 幂等=补传清且不重复）。
 */
const E2E_PASSWORD = 'e2e-pass-123456'
const FIXTURE_BUY_DATE = '2026-01-15'

let fixtureItemIds: number[] = []
let createdStocktakeId: number | null = null

test.beforeEach(() => {
  test.skip(test.info().project.name !== 'desktop-chromium', '仅 desktop-chromium 项目执行')
})

async function login(page: Page, username: string): Promise<void> {
  await page.goto('/login')
  await page.fill('#login-username', username)
  await page.fill('#login-password', E2E_PASSWORD)
  await page.getByRole('button', { name: 'ログイン' }).click()
  await expect(page.locator('.home-welcome, .dashboard-view')).toBeVisible()
}

async function unwrap<T>(response: APIResponse): Promise<T> {
  expect(response.ok(), `API ${response.status()} ${await response.text()}`).toBeTruthy()
  const body = (await response.json()) as { code: number; message: string; data: T }
  expect(body.code, body.message).toBe(0)
  return body.data
}

interface VenueRow {
  id: number
  code: string
}

interface ItemSummary {
  id: number
  itemCode: string
}

interface StocktakeSummary {
  id: number
  scannedCount: number
}

async function seededVenueId(page: Page): Promise<number> {
  const venues = await unwrap<VenueRow[]>(await page.request.get('/api/venues?enabled=true'))
  return venues.find((venue) => venue.code === 'HT')!.id
}

async function createInTransitItem(page: Page, venueId: number, warehouse: number): Promise<ItemSummary> {
  const item = await unwrap<ItemSummary>(
    await page.request.post('/api/items', {
      data: {
        clientReqId: crypto.randomUUID(),
        venueId,
        buyDate: FIXTURE_BUY_DATE,
        purchasePrice: 1000,
        warehouse,
      },
    }),
  )
  fixtureItemIds.push(item.id)
  return item
}

async function arrive(page: Page, itemId: number): Promise<void> {
  await unwrap(
    await page.request.post('/api/inventory/arrivals', {
      data: { items: [{ itemId, clientReqId: crypto.randomUUID() }] },
    }),
  )
}

test.afterEach(async ({ request }) => {
  await request.post('/api/auth/login', {
    form: { username: 'editor', password: E2E_PASSWORD },
  })
  if (createdStocktakeId != null) {
    await request
      .post(`/api/stocktakes/${createdStocktakeId}/cancel`)
      .catch(() => undefined) // 已 close 的历史单留档不影响后续用例
    createdStocktakeId = null
  }
  for (const id of fixtureItemIds) {
    await request
      .post(`/api/items/${id}/void`, {
        data: { clientReqId: crypto.randomUUID(), reason: 'e2e offline spec cleanup' },
      })
      .catch(() => undefined)
  }
  fixtureItemIds = []
})

test.describe('offline and weak-network flows (desktop-chromium)', () => {
  test('stocktake scan queues offline, replays once online, and unblocks close', async ({ page }) => {
    await login(page, 'editor')
    const venueId = await seededVenueId(page)
    const item = await createInTransitItem(page, venueId, 1)
    await arrive(page, item.id)

    // 发起名古屋仓盘点 → 会话页就绪
    await page.goto('/stocktake')
    await page.locator('.stocktake-wh-option', { hasText: '名古屋倉庫' }).click()
    await page.getByRole('button', { name: '棚卸を開始する' }).click()
    await expect(page).toHaveURL(/\/stocktake\/\d+$/)
    createdStocktakeId = Number(page.url().match(/\/stocktake\/(\d+)$/)![1])
    await expect(page.getByText('スキャン済み 0 件')).toBeVisible()

    // ---- 断网：照记本地（离线卡只显码、计数含队列、close 拦截）
    await page.context().setOffline(true)
    await page.fill('#stocktake-manual-input', item.itemCode)
    await page.getByRole('button', { name: '検索' }).click()

    await expect(page.locator('.session-card-main')).toHaveText(item.itemCode)
    await expect(page.getByText('オフラインで記録しました')).toBeVisible()
    await expect(page.locator('.session-card img')).toHaveCount(0) // 详情离线拿不到
    await expect(page.getByText('スキャン済み 1 件')).toBeVisible()
    await expect(page.getByText('未送信のスキャンが 1 件あります')).toBeVisible()
    await expect(page.locator('.session-actions .kcgl-btn-primary')).toBeDisabled()

    // ---- 恢复：online 事件自动回放 → 队列清空 → 口径切服务端、close 解锁
    await page.context().setOffline(false)

    await expect(page.locator('.session-actions .kcgl-btn-primary')).toBeEnabled()
    await expect(page.getByText('スキャン済み 1 件')).toBeVisible()
    await expect(page.getByText('未送信のスキャンが 1 件あります')).toHaveCount(0)

    // 终态以服务端为准：恰记 1 件（幂等回放不重复计数）
    const summary = await unwrap<StocktakeSummary>(
      await page.request.get(`/api/stocktakes/${createdStocktakeId}`),
    )
    expect(summary.scannedCount).toBe(1)
  })

  test('photo upload backs off while the image API is down and delivers exactly once on recovery', async ({ page }) => {
    await login(page, 'editor')
    await page.goto('/entry')

    // 会场（picker 首项）+ 单价 + 照片（压缩完成=1/9）
    await page.locator('.van-field').first().click()
    await page.locator('.van-picker__confirm').click()
    const priceInput = page.locator('.van-field input').nth(2)
    await priceInput.fill('1000')
    await priceInput.blur()
    await page.locator('input[type="file"][capture]').setInputFiles({
      name: 'photo.jpg',
      mimeType: 'image/jpeg',
      buffer: TEST_JPEG,
    })
    await expect(page.locator('.entry-photo-count')).toHaveText('1/9', { timeout: 10_000 })

    // 断 /api/images（保存走 /api/items 不受影响）：上传失败进退避
    await page.route('**/api/images', (route) => route.abort())
    await page.locator('button[type="submit"]').click()

    const code = (await page.locator('.entry-success-code').textContent())?.trim() ?? ''
    // 场次前缀不绑死：本 spec 按字母序跑在 excel.spec 之后，picker 首项已不是
    // 种子 HT 而是其建的 EX（M5-④ 同类套件序教训）；带档 X 由单价 1000 决定
    expect(code).toMatch(/^[A-Z]{2}\d{1,2}-A\d+X$/)
    // 退避等待中：角标停在送信中（pending 也计活跃）
    await expect(page.locator('.entry-success-upload.is-active')).toContainText('1枚を送信中')

    // 恢复：online 事件清零重试时钟立即冲 → 出清翻绿
    await page.unroute('**/api/images')
    await page.evaluate(() => window.dispatchEvent(new Event('online')))
    await expect(page.locator('.entry-success-upload.is-done')).toContainText('写真1枚を送信しました', {
      timeout: 15_000,
    })

    // 不重复：服务端恰 1 张（clientUuid 幂等——abort 后重试同一字节不落双份）
    // by-code 返回 {item, thumbUrl, reEntry} 包装（作废反链同响应），本体在 .item
    const found = await unwrap<{ item: ItemSummary }>(
      await page.request.get(`/api/items/by-code/${code}`),
    )
    fixtureItemIds.push(found.item.id)
    const images = await unwrap<unknown[]>(
      await page.request.get(`/api/items/${found.item.id}/images`),
    )
    expect(images).toHaveLength(1)
  })
})
