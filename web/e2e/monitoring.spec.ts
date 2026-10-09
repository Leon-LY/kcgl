import { expect, test, type Page } from '@playwright/test'

/**
 * 監視三页（M5-④，docs/01 9.3）：台帳ブラウズ/操作ログ/システム状況——
 * 管理员全链路（自种数据：本 spec 内经 /entry 录一件 → 台帳首行=该件新規登録 →
 * 前缀筛选收窄 → 行点击进商品详情；操作日志动作/操作人筛选 + 展开留痕 JSON；
 * 系统状况速览卡 + 整合性チェック全绿 + 诊断包下载）+ 编辑者/查看者越权回跳。
 * 台帳/日志只增不改不删（验收 9）：三页均无写入口是刻意设计。
 * 文件名排序在 entry/excel/items 之后执行（自种数据不依赖他 spec，但
 * Excel 批次表断言依赖 excel.spec 已产生导入历史——套件串行共享库）。
 */
const E2E_PASSWORD = 'e2e-pass-123456'

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

test.describe('monitoring pages (desktop-chromium)', () => {
  test('admin browses the ledger: seeded item appears newest, filters narrow, and row click opens the item', async ({ page }) => {
    await login(page, 'admin')

    // 自种数据：录一件（会场=picker 首项——前序 excel.spec 建的 EX 排在 HT 前，
    // 断言全程用成功页回显号，不绑会场；1000 円 → 档位 X）
    await page.goto('/entry')
    await page.locator('.van-field').first().click()
    await page.locator('.van-picker__confirm').click()
    await page.locator('.van-field input').nth(2).fill('1000')
    await expect(page.locator('.entry-band')).toContainText('X')
    await page.locator('button[type="submit"]').click()
    await expect(page.locator('.entry-success-code')).toBeVisible()
    const code = await page.locator('.entry-success-code').textContent()

    // 台帳：id 倒序 → 首行=刚录入的那件。CREATE 行不占仓账（wh 双侧 NULL、
    // qty 0、stock —→0：登记语义，仓流转自入库起算——ItemCodeTxService 契约）
    await page.goto('/ledgers')
    await expect(page).toHaveURL(/\/ledgers$/)
    await expect(page.locator('.page-header-title')).toHaveText('台帳ブラウズ')
    // 回归（D-161）：两个下拉的首载默认态必须是「すべて」，不是回落渲染的最后一个选项
    // ——对象哨兵曾让「種類」显示成「その他」、「倉庫」显示成「福岡倉庫」。
    await expect(page.locator('.ledgers-filter-type .el-select__placeholder')).toHaveText('すべての種類')
    await expect(page.locator('.ledgers-filter-wh .el-select__placeholder')).toHaveText('すべての倉庫')
    const rows = page.locator('.ledgers-table .el-table__row')
    await expect(rows.first()).toBeVisible()
    const countBefore = await rows.count()
    await expect(rows.first()).toContainText(code!)
    await expect(rows.first()).toContainText('新規登録')

    // 前缀筛选 → 只剩该件流水（号唯一，CREATE 一行）
    await page.getByPlaceholder('管理番号（前方一致）').fill(code!)
    await page.getByPlaceholder('管理番号（前方一致）').press('Enter')
    await expect(rows).toHaveCount(1)
    await expect(rows.first()).toContainText(code!)

    // 清空条件 → 恢复全量
    await page.getByRole('button', { name: '条件をクリア' }).click()
    await expect(rows).toHaveCount(countBefore)

    // 行点击 → 商品详情
    await rows.first().click()
    await expect(page).toHaveURL(/\/items\/\d+$/)
  })

  test('admin queries operation logs: action prefix, operator narrowing, and expandable detail JSON', async ({ page }) => {
    await login(page, 'admin')
    await page.goto('/admin/logs')
    await expect(page.locator('.page-header-title')).toHaveText('操作ログ')

    const rows = page.locator('.el-table__row')
    await expect(rows.first()).toBeVisible()
    // 登录不写日志 → 首行=上一用例录入的 ITEM_CREATE（操作人留痕=用户名 admin——
    // 台帳侧才是显示名，两口径刻意分流）
    await expect(rows.first()).toContainText('ITEM_CREATE')
    await expect(rows.first()).toContainText('admin')
    // 实体列带 #id 拼接
    await expect(rows.first()).toContainText(/#\d+/)

    // 动作前缀筛选：ITEM_ → 行全部为商品动作（等响应落地再操作——
    // 旧首行本就含 ITEM_，纯文本断言会吃陈旧 DOM，展开点击撞上重渲染即 detach）
    const filteredResponse = page.waitForResponse((r) =>
      r.url().includes('/api/operation-logs?action=ITEM_') && r.request().method() === 'GET')
    await page.getByPlaceholder('操作の種類（先頭の文字が一致）').fill('ITEM_')
    await page.getByPlaceholder('操作の種類（先頭の文字が一致）').press('Enter')
    await filteredResponse
    await expect(rows.first()).toContainText('ITEM_')

    // 展开首行 → 留痕 JSON 原样（ITEM_CREATE 明细含 itemCode 键）
    await rows.first().locator('.el-table__expand-icon').click()
    await expect(page.locator('.oplogs-detail').first()).toContainText('"itemCode"')

    // 操作人筛选收窄（留痕键=用户名）：admin → 首行仍是本 spec 录入的那条
    await page.getByPlaceholder('操作者').fill('admin')
    await page.getByPlaceholder('操作者').press('Enter')
    await expect(rows.first()).toContainText('ITEM_CREATE')
    await expect(rows.first()).toContainText('admin')
  })

  test('admin views system status, runs the all-green self-check, and exports diagnostics', async ({ page }) => {
    await login(page, 'admin')
    await page.goto('/admin/system')
    await expect(page.locator('.page-header-title')).toHaveText('システム状況')

    // 速览卡：版本/起動/稼働時間 + 资源（堆/池/磁盘）+ 数据量与号引擎
    await expect(page.locator('.system-stat.is-version')).toBeVisible()
    await expect(page.locator('.system-stat.is-startedAt')).toBeVisible()
    await expect(page.locator('.system-stat.is-heap')).toBeVisible()
    await expect(page.locator('.system-stat.is-pool')).toBeVisible()
    await expect(page.locator('.system-stat.is-items')).toBeVisible()
    await expect(page.locator('.system-stat.is-codeSkipped')).toBeVisible()

    // 直近バックアップ（M7）：E2E 栈未配 KCGL_BACKUP_STATUS → null=不可知占位（显示
    // 但不当故障——部署环境 backup.sh 产出状态文件后显示实际时刻，>25h 标红）
    await expect(page.locator('.system-stat.is-backup')).toBeVisible()
    await expect(page.locator('.system-stat.is-backup dd')).toHaveText('利用不可')
    await expect(page.locator('.system-stat.is-backup dd.is-danger')).toHaveCount(0)

    // 近 7 日前端错误：固定 7 格（缺日补零）
    await expect(page.locator('.system-error-bar')).toHaveCount(7)

    // Excel 导入近况（excel.spec 已产生历史）+ 告警区
    await expect(page.locator('.system-batch-table .el-table__row').first()).toBeVisible()
    await expect(page.locator('.system-alert-table')).toBeVisible()

    // 整合性チェック（旧称・帳実自検）→ 五节全 OK
    await page.getByRole('button', { name: '整合性チェックを実行' }).click()
    await expect(page.locator('.system-check-summary')).toHaveText('異常なし')
    await expect(page.locator('.system-check-badge.is-ok')).toHaveCount(5)

    // 诊断包下载：Content-Disposition 文件名 kcgl-diagnostics-*.json
    const downloadPromise = page.waitForEvent('download')
    await page.getByRole('button', { name: '診断情報をエクスポート' }).click()
    const download = await downloadPromise
    expect(download.suggestedFilename()).toMatch(/^kcgl-diagnostics-.+\.json$/)
  })

  test('editor has no monitor links and is bounced home from all three URLs', async ({ page }) => {
    await login(page, 'editor')

    const nav = page.locator('.shell-nav-link')
    await expect(nav.filter({ hasText: '台帳' })).toHaveCount(0)
    await expect(nav.filter({ hasText: '操作ログ' })).toHaveCount(0)
    await expect(nav.filter({ hasText: 'システム状況' })).toHaveCount(0)

    for (const url of ['/ledgers', '/admin/logs', '/admin/system']) {
      await page.goto(url)
      await expect(page).toHaveURL(/\/dashboard$/)
    }
    await expect(page.locator('.dashboard-view')).toBeVisible()
  })

  test('viewer cannot reach the monitoring pages either', async ({ page }) => {
    await login(page, 'viewer')
    await page.goto('/ledgers')
    await expect(page).toHaveURL(/\/dashboard$/)
    await page.goto('/admin/system')
    await expect(page).toHaveURL(/\/dashboard$/)
  })
})
