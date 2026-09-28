import { expect, test, type APIResponse, type Page } from '@playwright/test'

/**
 * M3-⑥ 盘点闭环 E2E（docs/03 G3）：发起（选仓）→ 手输码照记（重复/他仓/
 * 非在库/冻结四类卡内警示）→ close 冻结 → 差异四分类人工裁决（LOSS CONFIRM/
 * GAIN IGNORE/WH_MISMATCH CONFIRM/FROZEN 仅可 IGNORE）→ 全处理完自动转已确认。
 * 服务端终态以 API 断言（前端横幅不算数）。夹具隔离三纪律同 arrival.spec
 * （过去桶/相对计数/afterEach 作废出清），另撤掉本用例创建的盘点单。
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
  warehouse: number
  voided: boolean
}

interface StocktakeSummary {
  id: number
  stocktakeNo: string
  status: number
  expectedCount: number | null
  scannedCount: number
  pendingDiffCount: number | null
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

async function voidItem(page: Page, itemId: number): Promise<void> {
  await unwrap(
    await page.request.post(`/api/items/${itemId}/void`, {
      data: { clientReqId: crypto.randomUUID(), reason: 'e2e stocktake frozen fixture' },
    }),
  )
}

test.afterEach(async ({ request }) => {
  if (createdStocktakeId != null) {
    // 撤单失败=已确认/已 close 的历史单，留作历史记录不影响后续用例（409 吞掉）
    await request.post('/api/auth/login', {
      form: { username: 'editor', password: E2E_PASSWORD },
    })
    await request
      .post(`/api/stocktakes/${createdStocktakeId}/cancel`)
      .catch(() => undefined)
    createdStocktakeId = null
  }
  if (fixtureItemIds.length === 0) {
    return
  }
  await request.post('/api/auth/login', {
    form: { username: 'editor', password: E2E_PASSWORD },
  })
  for (const id of fixtureItemIds) {
    await request
      .post(`/api/items/${id}/void`, {
        data: { clientReqId: crypto.randomUUID(), reason: 'e2e stocktake spec cleanup' },
      })
      .catch(() => undefined) // 已作废件（冻结夹具）再作废 409，忽略
  }
  fixtureItemIds = []
})

test.describe('stocktake full flow (desktop-chromium)', () => {
  test('editor runs a stocktake end to end: start, record with warnings, close, resolve all four diff types, auto-confirm', async ({ page }) => {
    await login(page, 'editor')
    const venueId = await seededVenueId(page)

    // 夹具（全部名古屋仓，除 D 福岡仓）：
    //   A 在库已扫（无差异）/ B 在库未扫（LOSS）/ C 在途照记（GAIN）/
    //   D 福岡在库照记（WH_MISMATCH）/ E 在库→作废照记（FROZEN）
    const itemA = await createInTransitItem(page, venueId, 1)
    const itemB = await createInTransitItem(page, venueId, 1)
    const itemC = await createInTransitItem(page, venueId, 1)
    const itemD = await createInTransitItem(page, venueId, 2)
    const itemE = await createInTransitItem(page, venueId, 1)
    for (const item of [itemA, itemB, itemD, itemE]) {
      await arrive(page, item.id)
    }
    await voidItem(page, itemE.id)

    // 发起：选名古屋仓 → 进入会话页
    await page.goto('/stocktake')
    await expect(page).toHaveTitle('棚卸｜在庫管理システム')
    await page.locator('.stocktake-wh-option', { hasText: '名古屋倉庫' }).click()
    await page.getByRole('button', { name: '棚卸を開始する' }).click()
    await expect(page).toHaveURL(/\/stocktake\/\d+$/)
    createdStocktakeId = Number(page.url().match(/\/stocktake\/(\d+)$/)![1])
    await expect(page.locator('.session-summary-no')).toBeVisible()
    await expect(page.getByText('スキャン済み 0 件')).toBeVisible()

    // 手输码照记：A 正常 → B 重复扫（repeated 不报错）→ C 非在库警示 →
    // D 他仓警示 → E 冻结警示（照记不拦，差异在 close 后裁决）
    async function scanCode(code: string): Promise<void> {
      await page.fill('#stocktake-manual-input', code)
      await page.getByRole('button', { name: '検索' }).click()
      await expect(page.locator('.session-card-main')).toHaveText(code)
    }

    await scanCode(itemA.itemCode)
    await expect(page.locator('.session-card-note')).toHaveCount(0)
    await expect(page.getByText('スキャン済み 1 件')).toBeVisible()

    await scanCode(itemA.itemCode)
    await expect(page.locator('.session-card-note')).toContainText('スキャン済みの商品です')
    await expect(page.getByText('スキャン済み 1 件')).toBeVisible() // 重复不计数

    await scanCode(itemC.itemCode)
    await expect(page.locator('.session-card-note.is-warning')).toContainText(
      'システム上は在庫以外の状態です',
    )
    await expect(page.getByText('スキャン済み 2 件')).toBeVisible()

    await scanCode(itemD.itemCode)
    await expect(page.locator('.session-card-note.is-warning')).toContainText('他倉庫の商品です')
    await expect(page.getByText('スキャン済み 3 件')).toBeVisible()

    await scanCode(itemE.itemCode)
    await expect(page.locator('.session-card-note.is-warning')).toContainText(
      '取り消し済み・削除済みの商品です',
    )
    await expect(page.getByText('スキャン済み 4 件')).toBeVisible()

    // close：确认弹层 → 冻结并生成差异
    await page.getByRole('button', { name: '棚卸を締める' }).click()
    await expect(page.locator('.session-dialog')).toBeVisible()
    await page.getByRole('button', { name: '締めて差異を作る' }).click()
    await expect(page).toHaveURL(new RegExp(`/stocktake/${createdStocktakeId}/diffs$`))

    // 差异四分类：B 在庫不足 / C 在庫超過 / D 倉庫違い（福岡→名古屋）/ E 凍結品
    await expect(page.getByText('未確認 4 件')).toBeVisible()
    const rowOf = (code: string) => page.locator('.diff-row', { hasText: code })
    await expect(rowOf(itemB.itemCode).locator('.diff-type')).toHaveText('在庫不足')
    await expect(rowOf(itemC.itemCode).locator('.diff-type')).toHaveText('在庫超過')
    await expect(rowOf(itemD.itemCode).locator('.diff-type')).toHaveText('倉庫違い')
    await expect(rowOf(itemD.itemCode).locator('.diff-wh')).toHaveText('福岡倉庫 → 名古屋倉庫')
    await expect(rowOf(itemE.itemCode).locator('.diff-type')).toHaveText('凍結品')
    await expect(rowOf(itemE.itemCode)).toContainText('調整できません')

    // LOSS（B）：两步确认 → 調整済み
    await rowOf(itemB.itemCode).getByRole('button', { name: '調整する' }).click()
    await expect(rowOf(itemB.itemCode)).toContainText('実行しますか')
    await rowOf(itemB.itemCode).getByRole('button', { name: 'はい' }).click()
    await expect(rowOf(itemB.itemCode).locator('.diff-confirm-status')).toHaveText('調整済み')
    await expect(page.getByText('未確認 3 件')).toBeVisible()

    // FROZEN（E）：禁止調整（无按钮）仅可忽略
    await expect(rowOf(itemE.itemCode).getByRole('button', { name: '調整する' })).toHaveCount(0)
    await rowOf(itemE.itemCode).getByRole('button', { name: '無視する' }).click()
    await expect(rowOf(itemE.itemCode).locator('.diff-confirm-status')).toHaveText('無視')

    // WH_MISMATCH（D）：确认=移入盘点仓
    await rowOf(itemD.itemCode).getByRole('button', { name: '調整する' }).click()
    await rowOf(itemD.itemCode).getByRole('button', { name: 'はい' }).click()
    await expect(rowOf(itemD.itemCode).locator('.diff-confirm-status')).toHaveText('調整済み')

    // GAIN（C）：忽略
    await rowOf(itemC.itemCode).getByRole('button', { name: '無視する' }).click()
    await expect(rowOf(itemC.itemCode).locator('.diff-confirm-status')).toHaveText('無視')

    // 全处理完 → 单据自动转已确认（allDone 横幅 = 重读摘要后的服务端终态）
    await expect(page.locator('.diff-alldone')).toContainText(
      'すべての差異を処理しました。棚卸を確認済みにしました',
    )
    await expect(page.getByText('未確認 0 件')).toBeVisible()

    // 服务端终态（前端横幅不算数）：
    //   B 盘亏确认→已出库；C 盘盈忽略→仍在途；D 仓错确认→移入名古屋；E 忽略→仍作废
    const detailB = await unwrap<ItemDetail>(await page.request.get(`/api/items/${itemB.id}`))
    expect(detailB.stockStatus).toBe(2)
    const detailC = await unwrap<ItemDetail>(await page.request.get(`/api/items/${itemC.id}`))
    expect(detailC.stockStatus).toBe(0)
    const detailD = await unwrap<ItemDetail>(await page.request.get(`/api/items/${itemD.id}`))
    expect(detailD.warehouse).toBe(1)
    const detailE = await unwrap<ItemDetail>(await page.request.get(`/api/items/${itemE.id}`))
    expect(detailE.voided).toBe(true)
    const summary = await unwrap<StocktakeSummary>(
      await page.request.get(`/api/stocktakes/${createdStocktakeId}`),
    )
    expect(summary.status).toBe(2)
    expect(summary.pendingDiffCount).toBe(0)

    // 列表回看：状态=確認済み（按单号定位，不依赖列表排序）
    await page.goto('/stocktake')
    const historyRow = page.locator('.stocktake-row', { hasText: summary.stocktakeNo })
    await expect(historyRow.locator('.stocktake-row-status')).toHaveText('確認済み')
  })
})
