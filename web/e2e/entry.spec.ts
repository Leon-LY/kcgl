import { expect, test, type Page } from '@playwright/test'

/**
 * M2-4 连续录入闭环（docs/03 G2 前置）：字典加载 → 会场/单价填写（全角归一化）
 * → 两级预览 → 保存 → 大字管理号 + QR + 本日计数；viewer 越权直敲回首页。
 * 字典种子由 e2e profile 的 E2eDataSeeder 提供（会场 HT + 档位 X）。
 * 用例有写副作用（item 落库），钉在 desktop-chromium 单次执行。
 */
const E2E_PASSWORD = 'e2e-pass-123456'

test.beforeEach(async () => {
  test.skip(test.info().project.name !== 'desktop-chromium', '仅 desktop-chromium 项目执行')
})

async function login(page: Page, username: string): Promise<void> {
  await page.goto('/login')
  await page.fill('#login-username', username)
  await page.fill('#login-password', E2E_PASSWORD)
  await page.getByRole('button', { name: 'ログイン' }).click()
  await expect(page.locator('.home-welcome')).toBeVisible()
}

test.describe('连续录入（desktop-chromium）', () => {
  test('录一件成功：全角单价归一化 + 预览目安 + 最终管理号/QR/计数', async ({ page }) => {
    await login(page, 'editor')
    await page.goto('/entry')
    await expect(page).toHaveTitle('商品登録｜在庫管理システム')

    // 会场选择（弹出 picker 确认第一项 = HT 飛騨古民具市）
    await page.locator('.van-field').first().click()
    await expect(page.locator('.van-picker')).toBeVisible()
    await page.locator('.van-picker__confirm').click()
    await expect(page.locator('.van-field input').first()).toHaveValue('飛騨古民具市')

    // 单价输入全角数字（IME 场景）→ blur 归一化
    const priceInput = page.locator('.van-field input').nth(2)
    await priceInput.fill('１０００')
    await priceInput.blur()
    await expect(priceInput).toHaveValue('1000')

    // 两级预览：本地档位 X + 防抖后的完整号目安（预览≠保留文案在场）
    await expect(page.locator('.entry-band')).toContainText('X')
    await expect(page.locator('.entry-preview-code')).toHaveText(/^HT[A-Z]\d+-A1X$/, {
      timeout: 5000,
    })
    await expect(page.locator('.entry-preview-note')).toContainText('確定番号は保存時に発行されます')

    // 保存 → 成功页：最终管理号与预览一致（单写者场景）+ QR + 本日 1 件目
    await page.locator('button[type="submit"]').click()
    await expect(page.locator('.entry-success-code')).toBeVisible()
    await expect(page.locator('.entry-success-code')).toHaveText(/^HT[A-Z]\d+-A1X$/)
    await expect(page.locator('.entry-success-qr')).toBeVisible()
    await expect(page.locator('.entry-success-count')).toContainText('1')
    await expect(page.locator('.entry-success-guide')).toContainText('油性ペン')

    // 继续录入 → 表单重挂、沿用上一件（会场/单价）
    await page.getByRole('button', { name: '続けて登録する' }).click()
    await expect(page.locator('.van-field input').first()).toHaveValue('飛騨古民具市')
    await expect(page.locator('.van-field input').nth(2)).toHaveValue('1000')
  })

  test('viewer 直敲 /entry → 路由守卫回首页（服务端 403 兜底之外的前端拦截）', async ({ page }) => {
    await login(page, 'viewer')
    await page.goto('/entry')
    await expect(page).toHaveURL(/\/$/)
    await expect(page.locator('.home-welcome')).toBeVisible()
  })
})
