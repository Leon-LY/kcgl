import { expect, test, type APIResponse, type Page } from '@playwright/test'

/**
 * M5-① 商品一覧/商品詳細 E2E（docs/03 G5-①）：kw 搜索（管理号精确链）+
 * 仓库筛选与条件クリア；行点击进详情四段式展示+取引履歴/ヤフー受注两标签页；
 * 编辑弹层全量 PUT（商品名/備考/棚番号改后刷新；号内字段不动=无分歧徽标）；
 * 作废→?reEntry= 深链转录入重录横幅+作废件从搜索消失；
 * 管理员回收站削除→理由留痕→復元→列表回归；viewer 只读（无回收站标签/无操作按钮）。
 * 共库隔离：本 spec 造的件（editor 两件其一作废留库、admin 一件恢复留库），
 * 后续 spec 均为相对断言（print 按当日区间、today 按个人会话口径），无污染。
 */
const E2E_PASSWORD = 'e2e-pass-123456'
const FIXTURE_BUY_DATE = '2026-01-15'

interface VenueRow {
  id: number
  code: string
}

interface ItemRow {
  id: number
  itemCode: string
}

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

async function seededVenueId(page: Page): Promise<number> {
  const venues = await unwrap<VenueRow[]>(await page.request.get('/api/venues?enabled=true'))
  return venues.find((venue) => venue.code === 'HT')!.id
}

async function createItem(page: Page, price: number, warehouse: number): Promise<ItemRow> {
  return unwrap<ItemRow>(
    await page.request.post('/api/items', {
      data: {
        clientReqId: crypto.randomUUID(),
        venueId: await seededVenueId(page),
        buyDate: FIXTURE_BUY_DATE,
        purchasePrice: price,
        warehouse,
      },
    }),
  )
}

// intlify 缺 key 告警（动态 i18n key 未兜底）在真实浏览器控制台可闻——
// E2E 层兜底断言；组件级回归用例见视图 spec 列探测用例
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

test.describe('item list and detail (desktop-chromium)', () => {
  test.beforeEach(() => {
    test.skip(test.info().project.name !== 'desktop-chromium', '仅 desktop-chromium 项目执行')
  })

  test('editor searches, filters, edits item, and voids into re-entry deep link', async ({ page }) => {
    await login(page, 'editor')
    // 夹具价必须落在唯一启用档位 X=[0,3000) 左闭右开内（seeder 只种 X；admin.spec
    // 跑在前面已把自己建的 Z 档停用——3000 恰被右开边界排除，越界即 404002）
    const itemA = await createItem(page, 1000, 1)
    const itemB = await createItem(page, 2000, 2)

    // ---- 列表：kw 管理号精确链命中单件
    await page.goto('/items')
    await expect(page.locator('.items-table')).toBeVisible()
    await page.getByPlaceholder('管理番号・商品名・会場・棚番号などで検索').fill(itemA.itemCode)
    await page.getByRole('button', { name: '検索' }).click()
    const aRow = page.locator('#pane-list .el-table__row', { hasText: itemA.itemCode })
    await expect(aRow).toHaveCount(1)
    await expect(page.locator('.items-count')).toContainText('全 1 件')

    // 条件クリア回全量（itemB 为最新件必在第一页）
    await page.getByRole('button', { name: '条件をクリア' }).click()
    await expect(page.locator('#pane-list .el-table__row', { hasText: itemB.itemCode })).toBeVisible()

    // 仓库筛选：福岡倉庫只剩 itemB
    await page.locator('.items-filter-wh').click()
    await page.locator('.el-select-dropdown__item', { hasText: '福岡倉庫' }).click()
    await expect(page.locator('#pane-list .el-table__row', { hasText: itemB.itemCode })).toBeVisible()
    await expect(page.locator('#pane-list .el-table__row', { hasText: itemA.itemCode })).toHaveCount(0)

    await page.getByRole('button', { name: '条件をクリア' }).click()
    await expect(page.locator('#pane-list .el-table__row', { hasText: itemA.itemCode })).toBeVisible()

    // ---- 详情：行点击进入，四段式+状态标签，无分歧徽标
    await page.locator('#pane-list .el-table__row', { hasText: itemA.itemCode }).click()
    await expect(page).toHaveURL(new RegExp(`/items/${itemA.id}$`))
    await expect(page.locator('.itemd-title .itemd-code')).toHaveText(itemA.itemCode)
    // 管理番号/状态标签在标题与基本情報字段两处渲染——strict 模式必须限定标题区
    await expect(page.locator('.itemd-title .itemd-tag', { hasText: '移動中' })).toHaveCount(1)
    for (const section of ['基本情報', '在庫・保管', '詳細情報', 'システム情報']) {
      await expect(page.locator('.el-descriptions__title', { hasText: section })).toBeVisible()
    }
    await expect(page.locator('.itemd-divergence')).toHaveCount(0)

    // ---- 编辑弹层：改商品名/棚番号/備考 → 保存 → 详情就地刷新（号不变）
    await page.getByRole('button', { name: '編集する' }).click()
    // EP 关闭弹层不卸载 DOM——一律 :visible 限定当前弹层（防 strict 命中历史弹层）
    const dialog = page.locator('.el-dialog:visible')
    await expect(dialog).toContainText('管理番号は変更されません')
    const field = (label: string) => dialog.locator('.itemd-field', { hasText: label })
    await field('商品名').locator('input').fill('E2E備前茶碗')
    await field('棚番号').locator('input').fill('E2-99')
    await field('備考').locator('textarea').fill('E2E備考')
    await dialog.getByRole('button', { name: '保存する' }).click()
    await expect(dialog).toBeHidden()
    await expect(page.locator('.itemd-body')).toContainText('E2E備前茶碗')
    await expect(page.locator('.itemd-body')).toContainText('E2-99')
    await expect(page.locator('.itemd-title .itemd-code')).toHaveText(itemA.itemCode)
    await expect(page.locator('.itemd-divergence')).toHaveCount(0)

    // ---- 取引履歴：录入流水在场；ヤフー受注：空态
    await page.locator('.el-tabs__item', { hasText: '取引履歴' }).click()
    await expect(page.locator('.el-table__row', { hasText: '新規登録' })).toHaveCount(1)
    await page.locator('.el-tabs__item', { hasText: 'ヤフー受注' }).click()
    await expect(page.getByText('受注履歴はありません')).toBeVisible()

    // ---- 作废：理由必填拦截 → 填理由 → 跳录入页重录横幅（?reEntry= 深链）
    await page.locator('.el-tabs__item', { hasText: '基本情報' }).click()
    await page.locator('.itemd-actions').getByRole('button', { name: '取り消して再登録' }).click()
    const voidDialog = page.locator('.el-dialog:visible')
    await voidDialog.getByRole('button', { name: '取り消して再入力へ' }).click()
    await expect(voidDialog.locator('.itemd-form-error')).toContainText('取り消し理由を入力してください')
    await voidDialog.locator('textarea').fill('E2E価格入力ミス')
    await voidDialog.getByRole('button', { name: '取り消して再入力へ' }).click()
    // ?reEntry= 参数在 EntryView onMounted 即刻清参（防刷新留痕），URL 断言只锚定
    // 页面；深链生效的证明=重录横幅（横幅仅由 query 深链/作废流设置 reEntrySource 渲染）
    await expect(page).toHaveURL(/\/entry$/)
    await expect(page.locator('.entry-reentry-title')).toContainText(itemA.itemCode)

    // 作废件从搜索消失（列表口径=未删未废）
    await page.goto('/items')
    await page.getByPlaceholder('管理番号・商品名・会場・棚番号などで検索').fill(itemA.itemCode)
    await page.getByRole('button', { name: '検索' }).click()
    await expect(page.locator('#pane-list .el-table__row', { hasText: itemA.itemCode })).toHaveCount(0)
    await expect(page.getByText('該当する商品がありません')).toBeVisible()
  })

  test('admin deletes an item to the recycle bin and restores it', async ({ page }) => {
    await login(page, 'admin')
    const itemC = await createItem(page, 1500, 1)

    // 详情页删除（理由可选，填上留痕）
    await page.goto(`/items/${itemC.id}`)
    await page.locator('.itemd-actions').getByRole('button', { name: '削除する' }).click()
    const dialog = page.locator('.el-dialog:visible')
    await expect(dialog).toContainText('「削除済み商品」から復元できます')
    await dialog.locator('textarea').fill('E2E整理')
    await dialog.getByRole('button', { name: '削除する' }).click()
    await expect(page).toHaveURL(/\/items$/)

    // 回收站标签：行在场，管理号+理由可见
    await page.locator('.el-tabs__item', { hasText: '削除済み商品' }).click()
    const cRow = page.locator('#pane-recycle .el-table__row', { hasText: itemC.itemCode })
    await expect(cRow).toBeVisible()
    await expect(cRow).toContainText('E2E整理')

    // 復元 → 回收站清空 → 列表回归（搜索定位）
    await cRow.getByRole('button', { name: '復元する' }).click()
    await expect(page.locator('#pane-recycle .el-table__row')).toHaveCount(0)
    await expect(page.getByText('削除済みの商品はありません')).toBeVisible()

    await page.locator('.el-tabs__item', { hasText: '商品一覧' }).click()
    await page.getByPlaceholder('管理番号・商品名・会場・棚番号などで検索').fill(itemC.itemCode)
    await page.getByRole('button', { name: '検索' }).click()
    await expect(page.locator('#pane-list .el-table__row', { hasText: itemC.itemCode })).toHaveCount(1)
  })

  test('viewer gets a read-only list and detail without action buttons', async ({ page }) => {
    await login(page, 'viewer')
    await page.goto('/items')

    // 无回收站标签（单标签）；列表可读
    await expect(page.locator('.el-tabs__item')).toHaveCount(1)
    await expect(page.locator('.items-table')).toBeVisible()

    // 行点击进详情：三个操作按钮全隐藏，信息段照常可读
    await page.locator('#pane-list .el-table__row').first().click()
    await expect(page).toHaveURL(/\/items\/\d+$/)
    await expect(page.locator('.itemd-title .itemd-code')).toBeVisible()
    await expect(page.getByRole('button', { name: '編集する' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '取り消して再登録' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '削除する' })).toHaveCount(0)
  })
})
