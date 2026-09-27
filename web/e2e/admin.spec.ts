import { expect, test, type Page } from '@playwright/test'

/**
 * 字典管理（M2-8b-2）：会场/价格档位/年代号三页 CRUD + 角色可达性
 * （会场=E+、档位/年代号=管理员；路由守卫+服务端 403 双兜底）。
 * 共库隔离（本 spec 按文件名最先执行）：夹具会场 ZZ/档位 Z 在用例尾部
 * 停用——enabled-only 查询（录入/打印下拉）恢复只剩种子 HT/X；
 * 年代号无停用语义，2041/Z 编辑为 2042（后续 spec 均用 2026=K 不受影响）。
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
  await expect(page.locator('.home-welcome')).toBeVisible()
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
  test('admin manages venues, price bands, and year codes end to end', async ({ page }) => {
    await login(page, 'admin')

    // 顶栏导航：管理员可见全部五个链接
    const nav = page.locator('.shell-nav-link')
    await expect(nav).toHaveCount(5)
    await expect(nav.filter({ hasText: '価格帯' })).toHaveCount(1)
    await expect(nav.filter({ hasText: '年代号' })).toHaveCount(1)

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

    // ---- 年代号：A-Z 已被种子 2016-2041 全占（年份↔代号双向唯一，Z 用尽后规则=A2 待定）
    // ——新增任何字母必撞唯一约束：断言服务端 409005 就地展示；编辑路径走 2041/Z → 2042
    await nav.filter({ hasText: '年代号' }).click()
    await expect(page).toHaveURL(/\/admin\/year-codes$/)
    await expect(page.locator('.el-table__row').first()).toContainText('2016')

    await fillCreateDialog(page, ['2042', 'M'])
    await expect(page.locator('.admin-form-error')).toContainText('既に登録されています')
    await page.locator('.el-dialog').getByRole('button', { name: 'キャンセル' }).click()
    await expect(page.locator('.el-dialog')).toBeHidden()

    const row2041 = page.locator('.el-table__row', { hasText: '2041' })
    await row2041.getByRole('button', { name: '編集' }).click()
    const editInputs = page.locator('.el-dialog input')
    await expect(editInputs.first()).toBeVisible()
    await editInputs.nth(0).fill('2042')
    await saveDialog(page)
    await expect(page.locator('.el-table__row', { hasText: '2042' })).toContainText('Z')
  })

  test('editor can manage venues but not price bands or year codes', async ({ page }) => {
    await login(page, 'editor')

    // 导航：编辑者=ホーム/ラベル印刷/会場 三链接（无价格档位/年代号）
    const nav = page.locator('.shell-nav-link')
    await expect(nav).toHaveCount(3)
    await expect(nav.filter({ hasText: '価格帯' })).toHaveCount(0)
    await expect(nav.filter({ hasText: '年代号' })).toHaveCount(0)

    // 会场页可达：新增/改名可用，无停用按钮
    await nav.filter({ hasText: '会場' }).click()
    await expect(page.locator('.admin-header button').first()).toBeVisible()
    await expect(page.getByRole('button', { name: '停止する' })).toHaveCount(0)

    // 直敲 URL → 路由守卫回首页（服务端 403 二次兜底）
    await page.goto('/admin/price-bands')
    await expect(page).toHaveURL(/\/$/)
    await page.goto('/admin/year-codes')
    await expect(page).toHaveURL(/\/$/)
  })

  test('viewer has no admin links and is bounced home from admin URLs', async ({ page }) => {
    await login(page, 'viewer')

    const nav = page.locator('.shell-nav-link')
    await expect(nav).toHaveCount(2)
    await expect(nav.filter({ hasText: '会場' })).toHaveCount(0)

    await page.goto('/admin/venues')
    await expect(page).toHaveURL(/\/$/)
  })
})
