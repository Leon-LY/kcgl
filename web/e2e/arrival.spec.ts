import { expect, test, type APIResponse, type Page } from '@playwright/test'

/**
 * M2-8a 到货核对闭环（docs/03 G2 前置）：在途清单渲染 → 仓库筛选 →
 * 卡片选择 → 批量确认入库（指定入库日）→ 成功横幅 + 清单出清 + 状态翻转到在库；
 * viewer 只读视图（无操作条/卡片禁用/权限说明）。
 * 在途数据经编辑者会话的 POST /api/items 造出（录入 UI 已由 entry.spec 覆盖）。
 * 隔离三纪律（单栈共享库，后续 spec 依赖干净基线）：
 *   1) 夹具落札日固定为过去日期——写 2026-01 桶，不碰 entry.spec 预览断言的「今日桶 A1」；
 *   2) 计数断言以「造数前基线 + 新增 N」相对化，重试遗留数据不破坏确定性；
 *   3) afterEach 以编辑者 API 会话作废全部夹具件——print 按创建日筛选且排除作废件，基线复原。
 * 用例有写副作用，钉在 desktop-chromium 单次执行。
 */
const E2E_PASSWORD = 'e2e-pass-123456'
const FIXTURE_BUY_DATE = '2026-01-15'

/** 本 spec 造出的夹具件（afterEach 统一作废出清）。 */
let fixtureItemIds: number[] = []

test.beforeEach(async () => {
  test.skip(test.info().project.name !== 'desktop-chromium', '仅 desktop-chromium 项目执行')
})

async function login(page: Page, username: string): Promise<void> {
  await page.goto('/login')
  await page.fill('#login-username', username)
  await page.fill('#login-password', E2E_PASSWORD)
  await page.getByRole('button', { name: 'ログイン' }).click()
  await expect(page.locator('.home-welcome, .dashboard-view')).toBeVisible()
}

/** JST 日历日（YYYY-MM-DD）；offsetDays 可回溯近几日。 */
function jstDate(offsetDays = 0): string {
  const base = Date.now() + offsetDays * 86_400_000
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Tokyo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(base))
}

/** 解统一信封；page.request 与浏览器共享会话 Cookie。 */
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
  warehouseInDate: string | null
}

async function seededVenueId(page: Page): Promise<number> {
  const venues = await unwrap<VenueRow[]>(await page.request.get('/api/venues?enabled=true'))
  return venues.find((venue) => venue.code === 'HT')!.id
}

async function pendingTotal(page: Page, warehouse: number | null): Promise<number> {
  const query = warehouse == null ? '' : `?warehouse=${warehouse}`
  const list = await unwrap<{ total: number }>(
    await page.request.get(`/api/inventory/arrivals/pending${query}`),
  )
  return list.total
}

/** 经编辑者会话造一件在途商品（录入页职责之外的夹具路径；登记进清理名单）。 */
async function createInTransitItem(
  page: Page,
  venueId: number,
  warehouse: number,
): Promise<ItemSummary> {
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

test.afterEach(async ({ request }) => {
  // 夹具件出清：作废后从打印/在途等一切「活跃件」视图消失（号继续占位=业务正确行为）
  if (fixtureItemIds.length === 0) {
    return
  }
  await request.post('/api/auth/login', {
    form: { username: 'editor', password: E2E_PASSWORD },
  })
  for (const id of fixtureItemIds) {
    await request.post(`/api/items/${id}/void`, {
      data: { clientReqId: crypto.randomUUID(), reason: 'e2e arrival spec cleanup' },
    })
  }
  fixtureItemIds = []
})

test.describe('arrival check (desktop-chromium)', () => {
  test('editor confirms arrivals end to end: pending list, warehouse filter, batch stock-in with explicit date, and the list clears', async ({ page }) => {
    await login(page, 'editor')
    const venueId = await seededVenueId(page)
    const baseline = await pendingTotal(page, null)
    const baselineFukuoka = await pendingTotal(page, 2)

    // 造两件在途：名古屋 1 + 福岡 1
    const nagoyaItem = await createInTransitItem(page, venueId, 1)
    const fukuokaItem = await createInTransitItem(page, venueId, 2)
    await expect(pendingTotal(page, null)).resolves.toBe(baseline + 2)

    // 清单渲染：计数 + 两张卡片（码/仓库标签/落札日）
    await page.goto('/arrival')
    await expect(page).toHaveTitle('入庫確認｜在庫管理システム')
    await expect(page.locator('.arrival-card')).toHaveCount(baseline + 2, { timeout: 10_000 })
    await expect(page.getByText(`入庫待ち ${baseline + 2} 件`)).toBeVisible()
    await expect(page.locator('.arrival-card', { hasText: nagoyaItem.itemCode })).toContainText('名古屋倉庫')
    await expect(page.locator('.arrival-card', { hasText: fukuokaItem.itemCode })).toContainText('福岡倉庫')
    await expect(page.locator('.arrival-card', { hasText: nagoyaItem.itemCode })).toContainText(
      `落札日 ${FIXTURE_BUY_DATE.replaceAll('-', '/')}`,
    )

    // 仓库筛选：福岡 → 只剩福岡件；全部 → 两件回来
    await page.locator('.arrival-filter-option').nth(2).click()
    await expect(page.locator('.arrival-card')).toHaveCount(baselineFukuoka + 1, { timeout: 10_000 })
    await expect(page.locator('.arrival-card', { hasText: fukuokaItem.itemCode })).toBeVisible()
    await expect(page.locator('.arrival-card', { hasText: nagoyaItem.itemCode })).toBeHidden()
    await page.locator('.arrival-filter-option').nth(0).click()
    await expect(page.locator('.arrival-card', { hasText: nagoyaItem.itemCode })).toBeVisible()

    // 选择两件 → 操作条激活 → 确认弹层（指定入库日 = 3 天前 JST）
    await page.locator('.arrival-card', { hasText: nagoyaItem.itemCode }).click()
    await page.locator('.arrival-card', { hasText: fukuokaItem.itemCode }).click()
    const actionButton = page.locator('.arrival-actionbar button')
    await expect(actionButton).toHaveText('2件を入庫確認')
    await actionButton.click()

    const dialog = page.locator('.arrival-dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('対象 2 件')
    const inDate = jstDate(-3)
    await dialog.locator('#arrival-in-date').fill(inDate)
    await dialog.getByRole('button', { name: '入庫する' }).click()

    // 成功横幅 + 清单出清（计数回落基线、两码消失）
    await expect(page.locator('.arrival-done')).toContainText('2件を入庫しました')
    await expect(page.locator('.arrival-card', { hasText: nagoyaItem.itemCode })).toHaveCount(0, {
      timeout: 10_000,
    })
    await expect(page.locator('.arrival-card', { hasText: fukuokaItem.itemCode })).toHaveCount(0)
    await expect(page.getByText(`入庫待ち ${baseline} 件`)).toBeVisible()
    if (baseline === 0) {
      await expect(page.getByText('入庫待ちの商品はありません')).toBeVisible()
    }

    // 状态翻转到在库 + 指定入库日落库（服务端是最终事实源，前端横幅不算数）
    for (const item of [nagoyaItem, fukuokaItem]) {
      const detail = await unwrap<ItemDetail>(await page.request.get(`/api/items/${item.id}`))
      expect(detail.stockStatus).toBe(1)
      expect(detail.warehouseInDate).toBe(inDate)
    }
  })

  test('viewer sees a read-only list: permission note shown, cards disabled, no action bar', async ({ page }) => {
    // 编辑者先造一件在途（viewer 无写入权限），再切 viewer 会话
    await login(page, 'editor')
    const venueId = await seededVenueId(page)
    const item = await createInTransitItem(page, venueId, 1)
    await unwrap(await page.request.post('/api/auth/logout'))

    await login(page, 'viewer')
    await page.goto('/arrival')
    const card = page.locator('.arrival-card', { hasText: item.itemCode })
    await expect(card).toBeVisible({ timeout: 10_000 })

    await expect(page.locator('.arrival-actionbar')).toHaveCount(0)
    await expect(page.getByText('入庫確認の操作には編集者以上の権限が必要です')).toBeVisible()
    await expect(card).toBeDisabled()
    await card.click({ force: true })
    await expect(card).not.toHaveClass(/is-selected/)
  })
})
