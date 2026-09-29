import { expect, test, type Page } from '@playwright/test'

/**
 * 管理后台（M2-8b-2/8b-3）：会场/价格档位/账号三页 CRUD + 角色可达性
 * （会场=账号=E+；档位/账号/设置管理=管理员；路由守卫+服务端 403 双兜底）
 * + 首启 checklist 卡片（管理员大盘置顶、印刷完成标记落库；M5-③ 桌面落地
 * 页=dashboard，越权回跳亦落大盘，D-072）。
 * 共库隔离（本 spec 按文件名最先执行）：夹具会场 ZZ/档位 Z 在用例尾部
 * 停用——enabled-only 查询（录入/打印下拉）恢复只剩种子 HT/X；
 * 账号 hanako 创建后停用留库（后续 spec 不查用户列表，无影响）。
 * E2E 库为一次性容器（每轮全新），无跨轮累积。
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

/** 打开新增弹层并按顺序填字段。 */
async function fillCreateDialog(page: Page, values: string[]): Promise<void> {
  await page.locator('.admin-header button').first().click()
  const inputs = page.locator('.el-dialog input')
  await expect(inputs.first()).toBeVisible()
  for (let i = 0; i < values.length; i++) {
    await inputs.nth(i).fill(values[i]!)
  }
  await page.locator('.el-dialog').getByRole('button', { name: '保存' }).click()
}

async function saveDialog(page: Page): Promise<void> {
  await page.locator('.el-dialog').getByRole('button', { name: '保存' }).click()
}

test.describe('dictionary admin (desktop-chromium)', () => {
  test('admin manages venues, price bands, and accounts end to end', async ({ page }) => {
    await login(page, 'admin')

    // 首启 checklist（大盘置顶卡片）：种子态=员工/会场/档位已完成，录件/印刷未完成
    await expect(page.locator('.setup-card')).toBeVisible()
    await expect(page.locator('.setup-step')).toHaveCount(5)
    await expect(page.locator('.setup-step.is-done')).toHaveCount(3)

    // 「印刷できた」→ 服务端落 sys_setting → 第 5 步打勾、按钮消失
    await page.getByRole('button', { name: '印刷できた' }).click()
    await expect(page.locator('.setup-step.is-done')).toHaveCount(4)
    await expect(page.getByRole('button', { name: '印刷できた' })).toHaveCount(0)

    // 侧边栏导航（M5-③ 分组）：管理员可见全部九链接
    // （ダッシュボード/商品/ヤフー/エクセル/ラベル印刷/会場/価格帯/アカウント/設定）
    const nav = page.locator('.shell-nav-link')
    await expect(nav).toHaveCount(9)
    await expect(nav.filter({ hasText: 'ダッシュボード' })).toHaveCount(1)
    await expect(nav.filter({ hasText: '商品' })).toHaveCount(1)
    await expect(nav.filter({ hasText: 'エクセル' })).toHaveCount(1)
    await expect(nav.filter({ hasText: 'ヤフー' })).toHaveCount(1)
    await expect(nav.filter({ hasText: '価格帯' })).toHaveCount(1)
    await expect(nav.filter({ hasText: 'アカウント' })).toHaveCount(1)
    await expect(nav.filter({ hasText: '設定' })).toHaveCount(1)

    // ---- 会场：新增 → 改名 → 停用（尾部停用=恢复 enabled 下拉种子态）
    await nav.filter({ hasText: '会場' }).click()
    await expect(page).toHaveURL(/\/admin\/venues$/)
    await expect(page.locator('.admin-title')).toHaveText('会場管理')
    await expect(page.locator('.el-table__row')).toHaveCount(1)

    await fillCreateDialog(page, ['ZZ', 'E2Eテスト会場'])
    const zzRow = page.locator('.el-table__row', { hasText: 'ZZ' })
    await expect(zzRow).toBeVisible()
    await expect(zzRow).toContainText('有効')

    await zzRow.getByRole('button', { name: '名前を変更' }).click()
    await page.locator('.el-dialog input').nth(1).fill('E2Eテスト会場（改）')
    await saveDialog(page)
    await expect(zzRow).toContainText('E2Eテスト会場（改）')

    await zzRow.getByRole('button', { name: '停止する' }).click()
    await expect(zzRow).toContainText('停止')

    // ---- 价格档位：新增 Z=5000円以上（无界上限）→ 人读区间 → 停用
    await nav.filter({ hasText: '価格帯' }).click()
    await expect(page).toHaveURL(/\/admin\/price-bands$/)
    await expect(page.locator('.el-table__row', { hasText: 'X' })).toContainText('0〜2,999円')

    await fillCreateDialog(page, ['Z', '5000', ''])
    const zRow = page.locator('.el-table__row', { hasText: 'Z' })
    await expect(zRow).toContainText('5,000円以上')
    await expect(zRow).toContainText('有効')

    await zRow.getByRole('button', { name: '停止する' }).click()
    await expect(zRow).toContainText('停止')

    // ---- 账号：新增（默认角色/语言）→ 密码重置一次性展示 → 停用
    await nav.filter({ hasText: 'アカウント' }).click()
    await expect(page).toHaveURL(/\/admin\/users$/)
    // 种子 4 用户（admin/editor/viewer/taro）；自身行（admin）无停用按钮
    await expect(page.locator('.el-table__row')).toHaveCount(4)
    const selfRow = page.locator('.el-table__row', { hasText: 'admin' })
    await expect(selfRow.getByRole('button', { name: '停止する' })).toHaveCount(0)

    await page.locator('.admin-header button').first().click()
    const userInputs = page.locator('.el-dialog input')
    await expect(userInputs.first()).toBeVisible()
    await userInputs.nth(0).fill('hanako')
    await userInputs.nth(1).fill('E2E花子')
    await userInputs.last().fill('E2e-Hanako-123456')
    await saveDialog(page)
    const hanakoRow = page.locator('.el-table__row', { hasText: 'hanako' })
    await expect(hanakoRow).toContainText('E2E花子')
    await expect(hanakoRow).toContainText('編集者')
    await expect(hanakoRow).toContainText('—')

    await hanakoRow.getByRole('button', { name: 'パスワード再発行' }).click()
    await expect(page.locator('.user-reset-password')).toBeVisible()
    // 一次性初始密码非空且与创建时不同（服务端生成）
    await expect(page.locator('.user-reset-password')).not.toContainText('E2e-Hanako-123456')
    await page.getByRole('button', { name: '閉じる' }).click()

    await hanakoRow.getByRole('button', { name: '停止する' }).click()
    await expect(hanakoRow).toContainText('停止')
  })

  test('editor can manage venues but not price bands or accounts', async ({ page }) => {
    await login(page, 'editor')

    // 导航：编辑者=ダッシュボード/商品/ヤフー/エクセル/ラベル印刷/会場 六链接
    // （无价格档位/账号/設定）
    const nav = page.locator('.shell-nav-link')
    await expect(nav).toHaveCount(6)
    await expect(nav.filter({ hasText: 'ダッシュボード' })).toHaveCount(1)
    await expect(nav.filter({ hasText: '商品' })).toHaveCount(1)
    await expect(nav.filter({ hasText: 'エクセル' })).toHaveCount(1)
    await expect(nav.filter({ hasText: 'ヤフー' })).toHaveCount(1)
    await expect(nav.filter({ hasText: '価格帯' })).toHaveCount(0)
    await expect(nav.filter({ hasText: 'アカウント' })).toHaveCount(0)
    await expect(nav.filter({ hasText: '設定' })).toHaveCount(0)
    // 编辑者大盘无 checklist 卡片（管理员引导）
    await expect(page.locator('.setup-card')).toHaveCount(0)

    // 会场页可达：新增/改名可用，无停用按钮
    await nav.filter({ hasText: '会場' }).click()
    await expect(page.locator('.admin-header button').first()).toBeVisible()
    await expect(page.getByRole('button', { name: '停止する' })).toHaveCount(0)

    // 直敲 URL → 路由守卫回大盘（服务端 403 二次兜底；桌面壳 '/' 即 dashboard）
    await page.goto('/admin/price-bands')
    await expect(page).toHaveURL(/\/dashboard$/)
    await page.goto('/admin/users')
    await expect(page).toHaveURL(/\/dashboard$/)
  })

  test('viewer has no admin links and is bounced home from admin URLs', async ({ page }) => {
    await login(page, 'viewer')

    // 导航：查看者=ダッシュボード/商品/ヤフー/エクセル/ラベル印刷 五链接
    // （列表/雅虎/Excel 报告与导出全员可达）
    const nav = page.locator('.shell-nav-link')
    await expect(nav).toHaveCount(5)
    await expect(nav.filter({ hasText: 'ダッシュボード' })).toHaveCount(1)
    await expect(nav.filter({ hasText: '商品' })).toHaveCount(1)
    await expect(nav.filter({ hasText: 'エクセル' })).toHaveCount(1)
    await expect(nav.filter({ hasText: 'ヤフー' })).toHaveCount(1)
    await expect(nav.filter({ hasText: '会場' })).toHaveCount(0)
    await expect(nav.filter({ hasText: '設定' })).toHaveCount(0)

    await page.goto('/admin/venues')
    await expect(page).toHaveURL(/\/dashboard$/)
  })
})
