import { expect, test, type APIResponse, type Page } from '@playwright/test'

/**
 * M3-④ 扫码页动作闭环 E2E（docs/03 G3）：手输码定位 → 动作菜单按状态只渲染
 * 合法动作（inventoryActions 边表镜像）→ 入库后卖出（价格录入）→ 状态翻转
 * （在途→在庫→出庫済み）→ 服务端利润/成交价断言 → 顾客退货回库后菜单复现。
 * 摄像头路径由真机矩阵覆盖，此处走手输兜底路径（同一 locate 链路）。
 * ledger 存在性由后端集成测试锚定（流水浏览端点随 M5 落地后可加 UI 断言）。
 */
const E2E_PASSWORD = 'e2e-pass-123456'
const FIXTURE_BUY_DATE = '2026-01-15'

let fixtureItemIds: number[] = []

test.beforeEach(() => {
  test.skip(test.info().project.name !== 'desktop-chromium', '仅 desktop-chromium 项目执行')
})

async function login(page: Page, username: string): Promise<void> {
  await page.goto('/login')
  await page.fill('#login-username', username)
  await page.fill('#login-password', E2E_PASSWORD)
  await page.getByRole('button', { name: 'ログイン' }).click()
  await expect(page.locator('.home-welcome')).toBeVisible()
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

interface ItemDetail extends ItemSummary {
  stockStatus: number
  saleStatus: number
  soldPrice: number | null
  totalCost: number | null
  profit: number | null
}

async function seededVenueId(page: Page): Promise<number> {
  const venues = await unwrap<VenueRow[]>(await page.request.get('/api/venues?enabled=true'))
  return venues.find((venue) => venue.code === 'HT')!.id
}

test.afterEach(async ({ request }) => {
  if (fixtureItemIds.length === 0) {
    return
  }
  await request.post('/api/auth/login', {
    form: { username: 'editor', password: E2E_PASSWORD },
  })
  for (const id of fixtureItemIds) {
    await request
      .post(`/api/items/${id}/void`, {
        data: { clientReqId: crypto.randomUUID(), reason: 'e2e scan spec cleanup' },
      })
      .catch(() => undefined)
  }
  fixtureItemIds = []
})

test.describe('scan page actions (desktop-chromium)', () => {
  test('editor locates by manual input and walks the action menu through state transitions: in-transit → in-stock → sold → returned', async ({ page }) => {
    await login(page, 'editor')
    const venueId = await seededVenueId(page)

    const item = await unwrap<ItemSummary>(
      await page.request.post('/api/items', {
        data: {
          clientReqId: crypto.randomUUID(),
          venueId,
          buyDate: FIXTURE_BUY_DATE,
          purchasePrice: 1000,
          warehouse: 1,
        },
      }),
    )
    fixtureItemIds.push(item.id)

    // 手输定位在途件：状态标签 + 菜单只给合法动作（在途=仅退回拍卖场）
    await page.goto('/scan')
    await page.fill('#scan-manual-input', item.itemCode)
    await page.getByRole('button', { name: '検索' }).click()
    await expect(page.locator('.scan-code')).toHaveText(item.itemCode)
    await expect(page.locator('.scan-tag', { hasText: '移動中' })).toBeVisible()
    await expect(page.locator('.scan-tag', { hasText: '未出品' })).toBeVisible()
    const transitActions = page.locator('.scan-actions .scan-action')
    await expect(transitActions).toHaveCount(1)
    await expect(transitActions).toHaveText(['会場へ返す'])
    await expect(page.getByRole('button', { name: '売却' })).toHaveCount(0)

    // 入库（到货核对页职责，此处经 API 置态）→ 重定位 → 在库菜单五动作
    await unwrap(
      await page.request.post('/api/inventory/arrivals', {
        data: { items: [{ itemId: item.id, clientReqId: crypto.randomUUID() }] },
      }),
    )
    await page.fill('#scan-manual-input', item.itemCode)
    await page.getByRole('button', { name: '検索' }).click()
    await expect(page.locator('.scan-tag', { hasText: '在庫' })).toBeVisible()
    await expect(page.locator('.scan-actions .scan-action')).toHaveText([
      '売却',
      '移動',
      '廃棄',
      '出品済みにする',
      '会場へ返す',
    ])

    // 卖出 3000（进货 1000 无费用）→ 成功横幅 + 状态翻转 + 利润落库
    await page.getByRole('button', { name: '売却' }).click()
    await expect(page.locator('.scan-dialog')).toBeVisible()
    await page.fill('#scan-sold-price', '3000')
    await page.getByRole('button', { name: '売却する' }).click()
    await expect(page.locator('.scan-done')).toContainText('売却を記録しました')
    await expect(page.locator('.scan-tag', { hasText: '出庫済み' })).toBeVisible()
    await expect(page.locator('.scan-tag', { hasText: '落札済み' })).toBeVisible()
    // 已出库且成交 → 仅可顾客退货
    await expect(page.locator('.scan-actions .scan-action')).toHaveText(['返品を受け取る'])

    const sold = await unwrap<ItemDetail>(await page.request.get(`/api/items/${item.id}`))
    expect(sold.stockStatus).toBe(2)
    expect(sold.saleStatus).toBe(2)
    expect(sold.soldPrice).toBe(3000)
    expect(sold.totalCost).toBe(1000)
    expect(sold.profit).toBe(2000)

    // 顾客退货 → 回到在库（销售态=取消，不再给上架标记入口）
    await page.getByRole('button', { name: '返品を受け取る' }).click()
    await expect(page.locator('.scan-dialog')).toBeVisible()
    await page.fill('#scan-return-note', '梱包破損のため返品')
    await page.getByRole('button', { name: '返品を記録する' }).click()
    await expect(page.locator('.scan-done')).toContainText('返品を受け取りました')
    await expect(page.locator('.scan-tag', { hasText: '在庫' })).toBeVisible()
    await expect(page.locator('.scan-tag', { hasText: 'キャンセル' })).toBeVisible()
    await expect(page.locator('.scan-actions .scan-action')).toHaveText([
      '売却',
      '移動',
      '廃棄',
      '会場へ返す',
    ])
  })
})
