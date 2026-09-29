import { expect, test, type APIRequestContext, type APIResponse, type Page } from '@playwright/test'
import { TEST_JPEG } from './fixtures'

/**
 * M3-③ 实时同步 E2E（docs/03 G3，需求一「多人同时操作实时一致」）：B 端坐在
 * 列表页不动，A 端（独立会话）经 API 操作 → B 端页面在 5s 内自行更新
 * （SSE 广播 → 500ms 防抖 → 粗粒度失效重取）。三视图各一条：在途清单长出/
 * 消失、盘点会话计数递增、差异页他端处理后待确认数清零并出 allDone。
 * B=浏览器会话（EventSource 长连接），A=`request` 夹具的独立 Cookie 会话。
 */
const E2E_PASSWORD = 'e2e-pass-123456'
const FIXTURE_BUY_DATE = '2026-01-15'
/** 需求承诺的可见性预算：操作→B 端 DOM 更新（SSE+防抖+重取）。 */
const SYNC_BUDGET_MS = 5_000

let fixtureItemIds: number[] = []
let createdStocktakeIds: number[] = []

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

/** A 端独立会话登录（request 夹具与浏览器 Cookie 完全隔离）。 */
async function loginRemote(request: APIRequestContext, username: string): Promise<void> {
  const response = await request.post('/api/auth/login', {
    form: { username, password: E2E_PASSWORD },
  })
  expect(response.ok()).toBeTruthy()
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
  status: number
  scannedCount: number
}

interface DiffRow {
  id: number
  itemCode: string
}

async function seededVenueId(request: APIRequestContext): Promise<number> {
  const venues = await unwrap<VenueRow[]>(await request.get('/api/venues?enabled=true'))
  return venues.find((venue) => venue.code === 'HT')!.id
}

async function createInTransitItem(
  request: APIRequestContext,
  venueId: number,
  warehouse: number,
): Promise<ItemSummary> {
  const item = await unwrap<ItemSummary>(
    await request.post('/api/items', {
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
  await loginRemote(request, 'editor')
  for (const id of createdStocktakeIds) {
    // 已 close/确认的单撤不掉（409）——历史记录不影响后续用例
    await request.post(`/api/stocktakes/${id}/cancel`).catch(() => undefined)
  }
  createdStocktakeIds = []
  for (const id of fixtureItemIds) {
    await request
      .post(`/api/items/${id}/void`, {
        data: { clientReqId: crypto.randomUUID(), reason: 'e2e sync spec cleanup' },
      })
      .catch(() => undefined)
  }
  fixtureItemIds = []
})

test.describe('realtime sync across sessions (desktop-chromium)', () => {
  test('arrival list: remote item creation appears and remote stock-in clears it without B touching anything', async ({ page, request }) => {
    // B=viewer（只读角色同样享受实时同步），先坐上在途清单页
    await login(page, 'viewer')
    await page.goto('/arrival')
    await expect(page.getByText('入庫確認の操作には編集者以上の権限が必要です')).toBeVisible()

    // A 端（admin 独立会话）录入一件在途 → B 端清单长出该件
    await loginRemote(request, 'admin')
    const venueId = await seededVenueId(request)
    const item = await createInTransitItem(request, venueId, 1)
    const card = page.locator('.arrival-card', { hasText: item.itemCode })
    await expect(card).toBeVisible({ timeout: SYNC_BUDGET_MS })

    // A 端照片异步补传（录入后数秒的真实时序）→ B 端无图卡 5s 内补上缩略图
    // （IMAGE 事件=真实用户故事「照片后到」的浏览器级证明）
    await unwrap(
      await request.post('/api/images', {
        multipart: {
          file: { name: 'photo.jpg', mimeType: 'image/jpeg', buffer: TEST_JPEG },
          clientUuid: crypto.randomUUID(),
          itemId: String(item.id),
        },
      }),
    )
    await expect(card.locator('.arrival-thumb img')).toBeVisible({ timeout: SYNC_BUDGET_MS })

    // A 端确认入库 → B 端清单出清（viewer 无任何写操作，纯被动刷新）
    await unwrap(
      await request.post('/api/inventory/arrivals', {
        data: { items: [{ itemId: item.id, clientReqId: crypto.randomUUID() }] },
      }),
    )
    await expect(card).toHaveCount(0, { timeout: SYNC_BUDGET_MS })
  })

  test('stocktake session: remote scan bumps the live count on every open session page', async ({ page, request }) => {
    // B=editor 发起名古屋仓盘点并守在会话页
    await login(page, 'editor')
    const venueId = await seededVenueId(page.request)
    const stocktake = await unwrap<StocktakeSummary>(
      await page.request.post('/api/stocktakes', { data: { warehouse: 1 } }),
    )
    createdStocktakeIds.push(stocktake.id)
    await page.goto(`/stocktake/${stocktake.id}`)
    await expect(page.locator('.session-summary-no')).toBeVisible()
    await expect(page.getByText('スキャン済み 0 件')).toBeVisible()

    // A 端（admin）扫一件 → B 端计数 5s 内 +1（两人同场盘点场景）
    await loginRemote(request, 'admin')
    const item = await createInTransitItem(request, venueId, 1)
    await unwrap(
      await request.post(`/api/stocktakes/${stocktake.id}/scans`, { data: { code: item.itemCode } }),
    )
    await expect(page.getByText('スキャン済み 1 件')).toBeVisible({ timeout: SYNC_BUDGET_MS })

    const summary = await unwrap<StocktakeSummary>(
      await page.request.get(`/api/stocktakes/${stocktake.id}`),
    )
    expect(summary.scannedCount).toBe(1)
  })

  test('diff view: remote resolution drops the pending count and flips the session to confirmed', async ({ page, request }) => {
    // B=editor 造一件在库未扫 → 发起并 close（1 条 LOSS 差异），守在差异页
    await login(page, 'editor')
    const venueId = await seededVenueId(page.request)
    const item = await createInTransitItem(page.request, venueId, 1)
    await unwrap(
      await page.request.post('/api/inventory/arrivals', {
        data: { items: [{ itemId: item.id, clientReqId: crypto.randomUUID() }] },
      }),
    )
    const stocktake = await unwrap<StocktakeSummary>(
      await page.request.post('/api/stocktakes', { data: { warehouse: 1 } }),
    )
    createdStocktakeIds.push(stocktake.id)
    await unwrap(await page.request.post(`/api/stocktakes/${stocktake.id}/close`))
    await page.goto(`/stocktake/${stocktake.id}/diffs`)
    await expect(page.getByText('未確認 1 件')).toBeVisible()
    await expect(page.locator('.diff-row', { hasText: item.itemCode })).toBeVisible()

    // A 端（admin）确认该差异 → B 端行状态/待确认数/单据终态 5s 内同步
    const diffs = await unwrap<{ rows: DiffRow[] }>(
      await page.request.get(`/api/stocktakes/${stocktake.id}/diffs`),
    )
    const diff = diffs.rows.find((row) => row.itemCode === item.itemCode)!
    await loginRemote(request, 'admin')
    await unwrap(
      await request.post(`/api/stocktakes/${stocktake.id}/diffs/${diff.id}`, {
        data: { action: 'CONFIRM', clientReqId: crypto.randomUUID() },
      }),
    )
    await expect(page.getByText('未確認 0 件')).toBeVisible({ timeout: SYNC_BUDGET_MS })
    await expect(page.locator('.diff-alldone')).toBeVisible({ timeout: SYNC_BUDGET_MS })

    const summary = await unwrap<StocktakeSummary>(
      await page.request.get(`/api/stocktakes/${stocktake.id}`),
    )
    expect(summary.status).toBe(2)
  })
})
