import ExcelJS from 'exceljs'
import { expect, test, type APIResponse, type Page } from '@playwright/test'

/**
 * M4 Excel 管线 E2E（docs/03 G4，D-058/D-060）：模板下载（双 Sheet 契约）→
 * 双模式导入（旧号 EXK1-A5X + 自动採番行，专用会场 EX 桶隔离→採番メモ A0→A5
 * 确定）→批次报告四计数→API by-code 双件落库→エクスポート単票抽出→
 * 同文件重传 409 就地提示；viewer 只读。
 * 夹具用 exceljs（D-060 E：npm xlsx 停更+CVE，安全汰换）。
 */
const E2E_PASSWORD = 'e2e-pass-123456'
const FIXTURE_BUY_DATE = '2026-01-15'

/** 19 列契约表头（后端 ExcelProperties 默认，D-058 E）。 */
const HEADER = [
  '管理番号', '会場コード', '落札日', '仕入単価', '手数料', '送料', '消費税', '倉庫',
  '入庫日', '棚番号', 'グループ番号', '撮影日', '備考', '商品名', '分類', '作者・窯元',
  'サイズ', '重量（g）', '販売チャネル',
] as const

interface VenueRow {
  id: number
  code: string
}

interface ItemRow {
  id: number
  itemCode: string
  stockStatus: number
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

/** 专用会场 EX（编辑者可建，docs/01 六节）：EX+2026/1 桶全库唯本 spec 写入。 */
async function ensureVenue(page: Page): Promise<VenueRow> {
  return unwrap<VenueRow>(
    await page.request.post('/api/venues', {
      data: { code: 'EX', name: 'エクセル確認会場' },
    }),
  )
}

/** by-code 响应为 {item, thumbUrl, reEntry} 包一层（ItemByCodeResponse）。 */
async function itemByCode(page: Page, code: string): Promise<ItemRow> {
  const found = await unwrap<{ item: ItemRow }>(
    await page.request.get(`/api/items/by-code/${code}`),
  )
  return found.item
}

/** 构造导入 xlsx：行 1=契约表头，行 2+=数据。 */
async function buildWorkbook(rows: (string | number)[][]): Promise<Buffer> {
  const workbook = new ExcelJS.Workbook()
  const sheet = workbook.addWorksheet('商品')
  sheet.addRow([...HEADER])
  for (const row of rows) {
    sheet.addRow(row)
  }
  return Buffer.from(await workbook.xlsx.writeBuffer())
}

/** 双模式夹具：旧号 A5X（band X：2500<3000）+ 空号行→自动採番 A6X。 */
async function dualModeWorkbook(): Promise<Buffer> {
  const empty = Array<string | number>(11).fill('')
  return buildWorkbook([
    ['EXK1-A5X', 'EX', FIXTURE_BUY_DATE, 2500, '', '', '', '名古屋', ...empty],
    ['', 'EX', '2026-01-16', 1200, '', '', '', '福岡', ...empty],
  ])
}

async function voidItem(page: Page, itemId: number): Promise<void> {
  await unwrap(
    await page.request.post(`/api/items/${itemId}/void`, {
      data: { clientReqId: crypto.randomUUID(), reason: 'e2e excel spec cleanup' },
    }),
  )
}

// intlify 缺 key 告警兜底（与 yahoo.spec 同纪律）
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

test.describe('excel pipeline (desktop-chromium)', () => {
  test.beforeEach(() => {
    test.skip(test.info().project.name !== 'desktop-chromium', '仅 desktop-chromium 项目执行')
  })

  test('editor downloads the template, imports dual-mode rows, exports single item, gets 409 on reupload', async ({ page }) => {
    await login(page, 'editor')
    await ensureVenue(page)
    const workbook = await dualModeWorkbook()

    // ---- 模板下载：双 Sheet 契约（商品=19 列表头；記入方法非空）
    await page.goto('/excel')
    const templateDownload = page.waitForEvent('download')
    await page.getByRole('button', { name: 'テンプレートをダウンロード' }).click()
    const template = await templateDownload
    expect(template.suggestedFilename()).toBe('商品登録テンプレート.xlsx')
    const templateBook = new ExcelJS.Workbook()
    await templateBook.xlsx.readFile(await template.path())
    const dataSheet = templateBook.getWorksheet('商品')!
    expect(dataSheet.getCell(1, 1).value).toBe('管理番号')
    expect(dataSheet.getCell(1, 19).value).toBe('販売チャネル')
    expect(dataSheet.rowCount).toBe(1)
    expect(templateBook.getWorksheet('記入方法')!.rowCount).toBeGreaterThan(1)

    // ---- 双模式导入：批次报告四计数 + 採番メモ A0→A5（EX 桶首轮导入=确定值）
    await page.setInputFiles('.excel-upload-input', {
      name: 'items.xlsx',
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: workbook,
    })
    const reportRow = page.locator('#pane-import .el-table__row')
    await expect(reportRow.locator('.excel-tag')).toHaveText('完了', { timeout: 10_000 })
    // 列序：展开/ファイル/状態/総行数/自動採番/既存番号/エラー行/採番メモ。
    // 採番メモ格式={会場コード}{年}-{月} {from}→{to}（ItemCodeTxService 装饰桶上下文）
    const cells = reportRow.locator('td')
    await expect(cells.nth(3)).toHaveText('2')
    await expect(cells.nth(4)).toHaveText('1')
    await expect(cells.nth(5)).toHaveText('1')
    await expect(cells.nth(6)).toHaveText('0')
    await expect(cells.nth(7)).toHaveText('EX2026-1 A0→A5')

    // 双件落库：旧号原样 + 生成号接续（在途/未出品）
    const oldItem = await itemByCode(page, 'EXK1-A5X')
    const generatedItem = await itemByCode(page, 'EXK1-A6X')
    expect(oldItem.stockStatus).toBe(0)
    expect(generatedItem.stockStatus).toBe(0)

    // ---- エクスポート：単票抽出（码条件优先，区间默认当日不校验）
    await page.getByRole('tab', { name: 'エクスポート' }).click()
    await page.fill('.excel-code input', 'EXK1-A5X')
    const exportDownload = page.waitForEvent('download')
    await page.getByRole('button', { name: 'エクスポート' }).click()
    const exportFile = await exportDownload
    expect(exportFile.suggestedFilename()).toMatch(/^商品一覧_\d{8}\.xlsx$/)
    const exportBook = new ExcelJS.Workbook()
    await exportBook.xlsx.readFile(await exportFile.path())
    const exportSheet = exportBook.worksheets[0]!
    expect(exportSheet.getCell(1, 1).value).toBe('管理番号')
    expect(exportSheet.actualRowCount).toBe(2)
    expect(String(exportSheet.getCell(2, 1).value)).toBe('EXK1-A5X')

    // ---- 同文件重传：sha 重复 409 就地提示（不弹新批次）
    await page.getByRole('tab', { name: 'インポート' }).click()
    await page.setInputFiles('.excel-upload-input', {
      name: 'items.xlsx',
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: workbook,
    })
    await expect(page.locator('.excel-upload .kcgl-error-box')).toHaveText(
      '同じ内容のExcelファイルは既にインポート済みです。',
    )
    await expect(page.locator('#pane-import .el-table__row')).toHaveCount(1)

    // 清理：双件作废出清（号占位不复用，EX 桶计数器留值无碍后续轮次）
    await voidItem(page, oldItem.id)
    await voidItem(page, generatedItem.id)
  })

  test('viewer sees the history but cannot download template nor upload', async ({ page }) => {
    await login(page, 'viewer')
    await page.goto('/excel')

    const buttons = page.locator('.excel-upload-row button')
    await expect(buttons).toHaveCount(2)
    for (let i = 0; i < 2; i++) {
      await expect(buttons.nth(i)).toBeDisabled()
    }
    await expect(page.locator('.excel-upload .kcgl-info-box')).toHaveText(
      'インポートには編集者以上の権限が必要です。',
    )
    // 导出全员（A19）：エクスポート标签可用
    await expect(page.getByRole('tab', { name: 'エクスポート' })).toBeVisible()
    await page.getByRole('tab', { name: 'エクスポート' }).click()
    await expect(page.getByRole('button', { name: 'エクスポート' })).toBeEnabled()
  })
})
