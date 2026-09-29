import { expect, test, type APIResponse, type Page } from '@playwright/test'

/**
 * 本日录入会话（M2-8b）：/today 个人当天清单（含作废=对数口径）+
 * 移动壳 van-tabbar 导航。种子账号由 e2e profile 的 E2eDataSeeder 提供。
 *
 * 共库隔离三纪律（同 arrival.spec，全套件共用一个数据库）：
 * ① 夹具 buyDate 固定过去日 2026-01-15 → 写 2026-01 序号桶，
 *    不推进 entry.spec 断言的「今日桶」计数器；
 * ② 计数断言一律相对基线（先 API 捕获本会话现状再断言 baseline+N）；
 * ③ afterEach 用独立 request 上下文补作废所有未作废夹具件
 *    （打印/在途查询过滤 voided=1 → 基线还原；today-session 虽含
 *    作废件，但本页仅本 spec 消费且全部走相对断言）。
 */
const E2E_PASSWORD = 'e2e-pass-123456'
const FIXTURE_BUY_DATE = '2026-01-15'

let fixtureItemIds: number[] = []
/** 已在用例内作废的件：afterEach 不重复作废（二连作废=409006）。 */
let voidedIds = new Set<number>()

/** 用例的项目过滤器（页面用例=desktop，tabbar 用例=mobile，各自单跑一次）。 */
function onlyOn(project: string): void {
  test.beforeEach(() => {
    test.skip(test.info().project.name !== project, `仅 ${project} 项目执行`)
  })
}

test.afterEach(async ({ request }) => {
  const loginResponse = await request.post('/api/auth/login', {
    form: { username: 'editor', password: E2E_PASSWORD },
  })
  if (!loginResponse.ok()) return
  for (const itemId of fixtureItemIds) {
    if (voidedIds.has(itemId)) continue
    await request.post(`/api/items/${itemId}/void`, {
      data: { clientReqId: crypto.randomUUID(), reason: 'e2e today spec cleanup' },
    })
  }
  fixtureItemIds = []
  voidedIds = new Set()
})

async function login(page: Page, username: string): Promise<void> {
  await page.goto('/login')
  await page.fill('#login-username', username)
  await page.fill('#login-password', E2E_PASSWORD)
  await page.getByRole('button', { name: 'ログイン' }).click()
  await expect(page.locator('.home-welcome, .dashboard-view')).toBeVisible()
}

async function unwrap<T>(response: APIResponse): Promise<T> {
  expect(response.ok(), `API ${response.status()}: ${await response.text()}`).toBeTruthy()
  const body = (await response.json()) as { code: number; message: string; data: T }
  expect(body.code, body.message).toBe(0)
  return body.data
}

interface VenueRow {
  id: number
  code: string
}

async function seededVenueId(page: Page): Promise<number> {
  const venues = await unwrap<VenueRow[]>(await page.request.get('/api/venues'))
  const venue = venues.find((row) => row.code === 'HT')
  expect(venue, '种子会场 HT 应存在').toBeDefined()
  return venue!.id
}

interface ItemCreated {
  id: number
  itemCode: string
}

async function createItem(page: Page, venueId: number): Promise<ItemCreated> {
  const data = await unwrap<ItemCreated>(await page.request.post('/api/items', {
    data: {
      clientReqId: crypto.randomUUID(),
      venueId,
      buyDate: FIXTURE_BUY_DATE,
      purchasePrice: 1000,
      warehouse: 1,
    },
  }))
  fixtureItemIds.push(data.id)
  return data
}

interface SessionRow {
  id: number
  itemCode: string
}

interface TodaySessionBody {
  activeCount: number
  voidedCount: number
  rows: SessionRow[]
}

async function fetchSession(page: Page): Promise<TodaySessionBody> {
  return unwrap<TodaySessionBody>(await page.request.get('/api/items/today-session'))
}

test.describe('today session page (desktop-chromium)', () => {
  onlyOn('desktop-chromium')

  test('editor sees today entries with counts, times, and the voided marker with reason', async ({ page }) => {
    await login(page, 'editor')
    const venueId = await seededVenueId(page)
    const baseline = await fetchSession(page)

    const first = await createItem(page, venueId)
    const second = await createItem(page, venueId)
    await unwrap(
      await page.request.post(`/api/items/${first.id}/void`, {
        data: { clientReqId: crypto.randomUUID(), reason: '価格入力ミス' },
      }),
    )
    voidedIds.add(first.id)

    await page.goto('/today')
    await expect(page).toHaveTitle('本日の登録｜在庫管理システム')
    await expect(page.locator('.today-count')).toHaveText(
      `本日 ${baseline.activeCount + 1} 件・取り消し ${baseline.voidedCount + 1} 件`,
    )

    // 两件按录入顺序出现在清单，作废件带划线+标签+理由，时刻为 HH:mm
    const rows = page.locator('.today-row')
    await expect(rows.filter({ hasText: first.itemCode })).toHaveClass(/is-voided/)
    await expect(rows.filter({ hasText: first.itemCode }).locator('.today-void-tag'))
      .toHaveText('取り消し')
    await expect(rows.filter({ hasText: first.itemCode })).toContainText('価格入力ミス')
    await expect(rows.filter({ hasText: second.itemCode }).locator('.today-void-tag')).toHaveCount(0)
    await expect(rows.filter({ hasText: second.itemCode }).locator('.today-time'))
      .toHaveText(/^\d{2}:\d{2}$/)
  })

  test('viewer sees an empty session of their own', async ({ page }) => {
    await login(page, 'viewer')
    await page.goto('/today')

    // viewer 无录入权限 → 自身会话恒为空（其他 spec 也不会以 viewer 落件）
    await expect(page.locator('.today-count')).toHaveText('本日 0 件・取り消し 0 件')
    await expect(page.locator('.today-empty')).toHaveText('本日の登録はまだありません')
  })
})

test.describe('mobile tabbar navigation (mobile-chromium)', () => {
  onlyOn('mobile-chromium')

  test('editor sees five tabs and the today tab navigates to /today', async ({ page }) => {
    await login(page, 'editor')

    const tabs = page.locator('.van-tabbar-item')
    await expect(tabs).toHaveCount(5)
    await expect(page.locator('.van-tabbar-item').nth(0)).toHaveText('ホーム')
    await expect(page.locator('.van-tabbar-item').nth(1)).toHaveText('商品登録')
    await expect(page.locator('.van-tabbar-item').nth(2)).toHaveText('入庫確認')
    await expect(page.locator('.van-tabbar-item').nth(3)).toHaveText('スキャン')
    await expect(page.locator('.van-tabbar-item').nth(4)).toHaveText('本日')

    await page.locator('.van-tabbar-item').nth(4).click()
    await expect(page).toHaveURL(/\/today$/)
    await expect(page.locator('.today-title')).toHaveText('本日の登録')
  })

  test('viewer has no entry tab (four tabs only)', async ({ page }) => {
    await login(page, 'viewer')

    const tabs = page.locator('.van-tabbar-item')
    await expect(tabs).toHaveCount(4)
    await expect(page.locator('.van-tabbar-item').filter({ hasText: '商品登録' })).toHaveCount(0)
  })
})
