import { expect, test, type APIResponse, type Page } from '@playwright/test'

/**
 * M4 雅虎管线 E2E（docs/03 G4）：MS932 CSV 上传→批次报告（编码/四计数）→
 * 出荷待ち拣货队列（滞留红标）→照合三视图→「この商品を売り上げる」深链扫码页
 * 成交出库→队列经 SSE/轮询清空→同文件重传 409 就地提示；viewer 只读；
 * 移动端出荷待ち卡片+深链定位。MS932 字节由固定片段表拼装（Node 无该编码器，
 * 动态段——管理号/价格/日期——纯 ASCII）。
 */
const E2E_PASSWORD = 'e2e-pass-123456'
const FIXTURE_BUY_DATE = '2026-01-15'

// MS932 片段（Python round-trip 校验；重生成脚本见 docs/03 M4-D 注记）
const MS932_HEADER = Buffer.from(
  'g0mBW4NOg1aDh4OTSUQsj6SVaYNSgVuDaCyMu43dib+KaSyXjo5Eib+KaSyP85HULI9vlWmT+o6eLI9Jl7mT+o6eDQo=',
  'base64',
)
const MS932_SOLD = Buffer.from('l46ORIKzguqC3IK1gr0=', 'base64')
const MS932_ONSALE = Buffer.from('j2+VaZKG', 'base64')

interface VenueRow {
  id: number
  code: string
}

interface ItemSummary {
  id: number
  itemCode: string
}

interface ImportBatch {
  id: number
  status: number
}

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

async function seededVenueId(page: Page): Promise<number> {
  const venues = await unwrap<VenueRow[]>(await page.request.get('/api/venues?enabled=true'))
  return venues.find((venue) => venue.code === 'HT')!.id
}

/** 录一件并入库（在库是 CSV 商品侧标记的前提，docs/01 7.2）。 */
async function createInStockItem(page: Page): Promise<ItemSummary> {
  const item = await unwrap<ItemSummary>(
    await page.request.post('/api/items', {
      data: {
        clientReqId: crypto.randomUUID(),
        venueId: await seededVenueId(page),
        buyDate: FIXTURE_BUY_DATE,
        purchasePrice: 1000,
        warehouse: 1,
      },
    }),
  )
  await unwrap(
    await page.request.post('/api/inventory/arrivals', {
      data: { items: [{ itemId: item.id, clientReqId: crypto.randomUUID() }] },
    }),
  )
  return item
}

/** 成交行（命中 item）+ 在售行（unmatched）：终了日 2026/1/12 → 滞留红标。 */
function soldCsvBytes(auctionId: string, itemCode: string): Buffer {
  return Buffer.concat([
    MS932_HEADER,
    Buffer.from(`${auctionId},${itemCode},5000,25000,`, 'latin1'),
    MS932_SOLD,
    Buffer.from(',2026/1/10 10:00,2026/1/12 21:00\r\n', 'latin1'),
    Buffer.from(`${auctionId}-x,ZZZZ-ZZ9X,2000,,`, 'latin1'),
    MS932_ONSALE,
    Buffer.from(',2026/1/10 11:00,\r\n', 'latin1'),
  ])
}

/** API 直传（移动端用例走接口备货，UI 上传链路由桌面用例覆盖）。 */
async function uploadCsv(page: Page, bytes: Buffer): Promise<ImportBatch> {
  return unwrap<ImportBatch>(
    await page.request.post('/api/yahoo/imports', {
      multipart: { file: { name: 'export.csv', mimeType: 'text/csv', buffer: bytes } },
    }),
  )
}

// intlify 缺 key 告警（动态 i18n key 未兜底）在真实浏览器控制台可闻——
// E2E 层兜底断言；组件级回归用例见 YahooView.spec.ts 列探测用例
const intlifyWarnings: string[] = []

test.beforeEach(({ page }) => {
  page.on('console', (message) => {
    if (message.type() === 'warning' && message.text().includes('[intlify] Not found')) {
      intlifyWarnings.push(message.text())
    }
  })
})

test.afterEach(() => {
  expect(intlifyWarnings, `intlify 缺 key 告警：${intlifyWarnings.join(' / ')}`).toEqual([])
  intlifyWarnings.length = 0
})

test.describe('yahoo csv pipeline (desktop-chromium)', () => {
  test.beforeEach(() => {
    test.skip(test.info().project.name !== 'desktop-chromium', '仅 desktop-chromium 项目执行')
  })

  test('editor imports MS932 CSV: report, pending queue, reconcile views, sell deep link, sha 409', async ({ page }) => {
    await login(page, 'editor')
    const item = await createInStockItem(page)
    const csv = soldCsvBytes('auc-e2e-01', item.itemCode)

    // 上传（同步段毫秒级受理）→ 轮询/SSE 接力终态 → 报告行：MS932 + 2/1/1
    await page.goto('/yahoo')
    await page.setInputFiles('.yahoo-upload-input', {
      name: 'export.csv',
      mimeType: 'text/csv',
      buffer: csv,
    })
    const reportRow = page.locator('#pane-import .el-table__row')
    await expect(reportRow).toContainText('完了')
    // 列序：展开/文件/状态/编码/总行/一致/不一致/既存更新/上传/完了時刻
    const cells = reportRow.locator('td')
    await expect(cells.nth(3)).toHaveText('MS932')
    await expect(cells.nth(4)).toHaveText('2')
    await expect(cells.nth(5)).toHaveText('1')
    await expect(cells.nth(6)).toHaveText('1')
    await expect(cells.nth(7)).toHaveText('0')

    // 出荷待ち：成交未出库 1 件，滞留红标（成交于 2026/1/12）
    await page.getByRole('tab', { name: '出荷待ち' }).click()
    await expect(page.locator('#pane-shipments .yahoo-section-count')).toHaveText('出荷待ち 1 件')
    const queueRow = page.locator('#pane-shipments .el-table__row')
    await expect(queueRow).toContainText(item.itemCode)
    await expect(queueRow).toContainText('￥25,000')
    await expect(queueRow).toContainText('出荷遅延')

    // 照合：落札済み・未出庫 1（滞留）；撤架视图空
    await page.getByRole('tab', { name: '照合' }).click()
    await expect(page.locator('#pane-reconcile')).toContainText('落札済み・未出庫（1）')
    await expect(page.locator('#pane-reconcile')).toContainText('落札なし・再出品待ち（0）')
    await expect(page.locator('#pane-reconcile')).toContainText('出庫済み・ヤフー出品中（取り下げ確認）（0）')
    await expect(page.locator('#pane-reconcile .yahoo-tag')).toHaveText(['滞留'])

    // 「この商品を売り上げる」深链：扫码页进页即定位，直接成交出库
    await page.getByRole('tab', { name: '出荷待ち' }).click()
    await page.getByRole('button', { name: 'この商品を売り上げる' }).click()
    await expect(page).toHaveURL(new RegExp(`/scan\\?code=${item.itemCode}$`))
    await expect(page.locator('.scan-code')).toHaveText(item.itemCode)
    await page.getByRole('button', { name: '売却' }).click()
    await page.fill('#scan-sold-price', '25000')
    await page.getByRole('button', { name: '売却する' }).click()
    await expect(page.locator('.scan-done')).toContainText('売却を記録しました')
    await expect(page.locator('.scan-tag', { hasText: '出庫済み' })).toBeVisible()

    // 卖出清账 → 出荷待ち清空（SSE/轮询刷新，无需手动 F5）
    await page.goto('/yahoo')
    await page.getByRole('tab', { name: '出荷待ち' }).click()
    await expect(page.locator('#pane-shipments .yahoo-section-count')).toHaveText('出荷待ち 0 件', {
      timeout: 10_000,
    })

    // 同文件重传：sha 重复 409 就地提示（不弹新批次）
    await page.getByRole('tab', { name: 'CSVインポート' }).click()
    await page.setInputFiles('.yahoo-upload-input', {
      name: 'export.csv',
      mimeType: 'text/csv',
      buffer: csv,
    })
    await expect(page.locator('.yahoo-upload .kcgl-error-box')).toHaveText(
      '同じ内容のCSVファイルは既にインポート済みです。',
    )
    await expect(page.locator('#pane-import .el-table__row')).toHaveCount(1)

    // 清理：卖出件作废出清
    await page.request.post(`/api/items/${item.id}/void`, {
      data: { clientReqId: crypto.randomUUID(), reason: 'e2e yahoo spec cleanup' },
    })
  })

  test('viewer reads the three tabs but cannot upload', async ({ page }) => {
    await login(page, 'viewer')
    await page.goto('/yahoo')

    const uploadButton = page.locator('.yahoo-upload button')
    await expect(uploadButton).toBeDisabled()
    await expect(page.locator('.yahoo-upload .kcgl-info-box')).toHaveText(
      'インポートには編集者以上の権限が必要です。',
    )
    await expect(page.getByRole('tab', { name: '出荷待ち' })).toBeVisible()
    await expect(page.getByRole('tab', { name: '照合' })).toBeVisible()
  })
})

test.describe('pending shipments mobile (mobile-chromium)', () => {
  test.beforeEach(() => {
    test.skip(test.info().project.name !== 'mobile-chromium', '仅 mobile-chromium 项目执行')
  })

  test('mobile queue card with delayed badge deep-links the scan page', async ({ page }) => {
    await login(page, 'editor')
    const item = await createInStockItem(page)

    // 接口直传备货（UI 上传链路由桌面用例锚定）；轮询至终态
    const batch = await uploadCsv(page, soldCsvBytes('auc-e2e-m1', item.itemCode))
    for (let i = 0; i < 50; i++) {
      const current = await unwrap<ImportBatch>(
        await page.request.get(`/api/yahoo/imports/${batch.id}`),
      )
      if (current.status === 1) {
        break
      }
      await page.waitForTimeout(200)
      if (i === 49) {
        throw new Error('E2E 批次 10s 内未完成')
      }
    }

    await page.goto('/pending-shipments')
    const card = page.locator('.shipment-card')
    await expect(card).toHaveCount(1)
    await expect(card.locator('.shipment-code')).toHaveText(item.itemCode)
    await expect(card.locator('.shipment-price')).toHaveText('￥25,000')
    await expect(card.locator('.shipment-delayed')).toHaveText('出荷遅延')

    await card.locator('.shipment-sell').click()
    await expect(page).toHaveURL(new RegExp(`/scan\\?code=${item.itemCode}$`))
    await expect(page.locator('.scan-code')).toHaveText(item.itemCode)

    await page.request.post(`/api/items/${item.id}/void`, {
      data: { clientReqId: crypto.randomUUID(), reason: 'e2e yahoo spec cleanup' },
    })
  })
})
