import { expect, test, type APIResponse, type Page } from '@playwright/test'
import { TEST_JPEG } from './fixtures'

/**
 * WebKit 冒烟（docs/01 E2E 节「+project: webkit 冒烟」）：iOS Safari 是最高优先
 * 适配目标（R1），Playwright WebKit + iPhone 视口/UA 是最接近的无真机代理——
 * 登录（iOS UA 解析移动壳）/录入拍照（压缩 Worker+canvas QR+FormData 上传）/
 * 扫码定位（动作菜单+AudioContext/vibrate 守卫）/盘点离线小队列（Dexie v2+
 * online 回放）四条关键路径在 WebKit 内核跑通。真机全项见 docs/qa/device-matrix.md
 * （G6）；SW/manifest 不在此断言——dev 栈不产出 SW（构建期验证+真机 P-0x）。
 */
const E2E_PASSWORD = 'e2e-pass-123456'
const FIXTURE_BUY_DATE = '2026-01-15'

let fixtureItemIds: number[] = []
let createdStocktakeId: number | null = null

test.beforeEach(() => {
  test.skip(test.info().project.name !== 'webkit-smoke', '仅 webkit-smoke 项目执行')
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
        data: { clientReqId: crypto.randomUUID(), reason: 'e2e webkit smoke cleanup' },
      })
      .catch(() => undefined)
  }
  fixtureItemIds = []
})

test.describe('webkit engine smoke (iOS proxy)', () => {
  test('login lands in the mobile shell and entry with photo completes', async ({ page }) => {
    await login(page, 'editor')
    // iOS UA → 移动壳（底栏 van-tabbar；桌面壳无此元素）
    await expect(page.locator('.van-tabbar')).toBeVisible()

    // 录入：压缩 Worker（D-071 同源资产）+ FormData 上传 + 成功页 canvas QR
    await page.goto('/entry')
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
    await page.locator('button[type="submit"]').click()

    const code = ((await page.locator('.entry-success-code').textContent()) ?? '').trim()
    // 套件序不绑会场（excel.spec 建过 EX 会场，picker 首项非种子 HT）；带档 X 由单价 1000 决定
    expect(code).toMatch(/^[A-Z]{2}\d{1,2}-A\d+X$/)
    await expect(page.locator('.entry-success-qr')).toBeVisible()
    await expect(page.locator('.entry-success-upload.is-done')).toContainText('写真1枚を送信しました', {
      timeout: 15_000,
    })

    // 服务端确实收到：by-code 返回 {item, thumbUrl, reEntry} 包装，本体在 .item
    const found = await unwrap<{ item: ItemSummary }>(
      await page.request.get(`/api/items/by-code/${code}`),
    )
    fixtureItemIds.push(found.item.id)
  })

  test('scan locate renders the action menu and the stocktake offline queue replays', async ({ page }) => {
    await login(page, 'editor')
    const venueId = await seededVenueId(page)
    const item = await createInTransitItem(page, venueId, 1)
    await arrive(page, item.id)

    // 扫码定位：在库五动作菜单（beep 的 webkitAudioContext/vibrate 守卫不炸 WebKit）
    await page.goto('/scan')
    await page.fill('#scan-manual-input', item.itemCode)
    await page.getByRole('button', { name: '検索' }).click()
    await expect(page.locator('.scan-code')).toHaveText(item.itemCode)
    await expect(page.locator('.scan-actions .scan-action')).toHaveText([
      '売却',
      '移動',
      '廃棄',
      '出品済みにする',
      '会場へ返す',
    ])

    // 盘点离线小队列（M6-②）：断网照记 → 恢复回放 → 服务端恰 1 件（幂等不重复计数）
    await page.goto('/stocktake')
    await page.locator('.stocktake-wh-option', { hasText: '名古屋倉庫' }).click()
    await page.getByRole('button', { name: '棚卸を開始する' }).click()
    await expect(page).toHaveURL(/\/stocktake\/\d+$/)
    createdStocktakeId = Number(page.url().match(/\/stocktake\/(\d+)$/)![1])
    await expect(page.getByText('スキャン済み 0 件')).toBeVisible()

    await page.context().setOffline(true)
    await page.fill('#stocktake-manual-input', item.itemCode)
    await page.getByRole('button', { name: '検索' }).click()
    await expect(page.getByText('オフラインで記録しました')).toBeVisible()
    await expect(page.getByText('スキャン済み 1 件')).toBeVisible()
    await expect(page.locator('.session-actions .kcgl-btn-primary')).toBeDisabled()

    // 恢复：WebKit 不保证随拦截解除派发 online 事件，显式触发以解耦浏览器信号实现
    await page.context().setOffline(false)
    await page.evaluate(() => window.dispatchEvent(new Event('online')))
    await expect(page.locator('.session-actions .kcgl-btn-primary')).toBeEnabled()
    await expect(page.getByText('未送信のスキャンが 1 件あります')).toHaveCount(0)

    const summary = await unwrap<StocktakeSummary>(
      await page.request.get(`/api/stocktakes/${createdStocktakeId}`),
    )
    expect(summary.scannedCount).toBe(1)
  })
})
