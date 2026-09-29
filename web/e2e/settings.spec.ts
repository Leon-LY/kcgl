import { expect, test, type Page } from '@playwright/test'

/**
 * 系统设置页（M5-③）：滞销阈值双键按序保存（黄红关系）+ 标签规格
 * （custom 尺寸）+ 打印页默认规格联动 + 越权回跳。
 * 落库值跨用例共享：本 spec 结束态=warn45/alarm100/custom60×40——
 * 排序上 print/items 在本 spec 之前执行，其后 spec 不读标签规格与阈值，
 * 无断言耦合。
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
  await expect(page.locator('.dashboard-view')).toBeVisible()
}

test.describe('settings (desktop-chromium)', () => {
  test('admin adjusts slow-move thresholds and they persist across reloads', async ({ page }) => {
    await login(page, 'admin')
    await page.locator('.shell-nav-link').filter({ hasText: '設定' }).click()
    await expect(page).toHaveURL(/\/admin\/settings$/)

    const slowCard = page.locator('.settings-card').filter({ hasText: '滞留しきい値' })
    // 初始默认（未落库回退）：30/90
    const inputs = slowCard.locator('.settings-input')
    await expect(inputs.nth(0)).toHaveValue('30')
    await expect(inputs.nth(1)).toHaveValue('90')

    // 改为 45/100 → 保存 → 成功提示
    await inputs.nth(0).fill('45')
    await inputs.nth(1).fill('100')
    await slowCard.getByRole('button', { name: '保存' }).click()
    await expect(slowCard.locator('.settings-saved')).toHaveText('保存しました')

    // 刷新回读：落库值生效（读侧不再回退默认）
    await page.reload()
    const slowCardAfter = page.locator('.settings-card').filter({ hasText: '滞留しきい値' })
    await expect(slowCardAfter.locator('.settings-input').nth(0)).toHaveValue('45')
    await expect(slowCardAfter.locator('.settings-input').nth(1)).toHaveValue('100')
  })

  test('cross-field validation rejects warn >= alarm without writing', async ({ page }) => {
    await login(page, 'admin')
    await page.goto('/admin/settings')

    const slowCard = page.locator('.settings-card').filter({ hasText: '滞留しきい値' })
    const inputs = slowCard.locator('.settings-input')
    // 现值 45/100（上一用例落库）：warn 抬到 150 ≥ alarm → 客户端拒绝
    await inputs.nth(0).fill('150')
    await slowCard.getByRole('button', { name: '保存' }).click()
    await expect(slowCard.locator('.settings-error'))
      .toHaveText('黄色しきい値は赤色しきい値より小さくしてください')

    // 拒绝不落库：刷新回读仍为 45/100
    await page.reload()
    const slowCardAfter = page.locator('.settings-card').filter({ hasText: '滞留しきい値' })
    await expect(slowCardAfter.locator('.settings-input').nth(0)).toHaveValue('45')
  })

  test('admin configures a custom label preset and the print page adopts it as default', async ({ page }) => {
    await login(page, 'admin')
    await page.goto('/admin/settings')

    const labelCard = page.locator('.settings-card').filter({ hasText: 'ラベル規格' })
    // 默认 small：尺寸输入不出现
    await expect(labelCard.locator('.settings-input')).toHaveCount(0)

    // 选 custom → 尺寸输入出现（回退默认 50×30）→ 改 60×40 → 保存
    await labelCard.locator('.settings-preset-option').filter({ hasText: 'カスタム' }).click()
    const dims = labelCard.locator('.settings-input')
    await expect(dims.nth(0)).toHaveValue('50')
    await dims.nth(0).fill('60')
    await dims.nth(1).fill('40')
    await labelCard.getByRole('button', { name: '保存' }).click()
    await expect(labelCard.locator('.settings-saved')).toHaveText('保存しました')

    // 打印页默认规格联动：custom（60×40）为选中预置
    await page.goto('/print')
    const customRadio = page.locator('.el-radio-button').filter({ hasText: 'カスタム（60×40）' })
    await expect(customRadio).toBeVisible()
    await expect(customRadio).toHaveClass(/is-active/)
  })

  test('viewer cannot reach the settings page (route guard on top of the server-side 403)', async ({ page }) => {
    await login(page, 'viewer')
    await page.goto('/admin/settings')
    await expect(page).toHaveURL(/\/dashboard$/)
    await expect(page.locator('.dashboard-view')).toBeVisible()
  })
})
