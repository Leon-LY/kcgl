import { expect, test, type Page } from '@playwright/test'

/**
 * M2-4 连续录入闭环（docs/03 G2 前置）：字典加载 → 会场/单价填写（全角归一化）
 * → 两级预览 → 保存 → 大字管理号 + QR + 本日计数；viewer 越权直敲回首页。
 * 字典种子由 e2e profile 的 E2eDataSeeder 提供（会场 HT + 档位 X）。
 * 用例有写副作用（item 落库），钉在 desktop-chromium 单次执行。
 */
const E2E_PASSWORD = 'e2e-pass-123456'

/** 320×240 蓝-白测试 JPEG（程序化生成，走真实压缩与后端解码全链路）。 */
const TEST_JPEG = Buffer.from(
  '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',
  'base64',
)

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

    // 相机入口选 1 张（7.5 全链路：浏览器压缩→Dexie→保存后绑定续传→后端校验重编码落盘）
    await page.locator('input[type="file"][capture]').setInputFiles({
      name: 'photo.jpg',
      mimeType: 'image/jpeg',
      buffer: TEST_JPEG,
    })
    await expect(page.locator('.entry-photo img')).toBeVisible()
    await expect(page.locator('.entry-photo-count')).toHaveText('1/9')
    // 拍照语义：撮影日自动=今天（JST 日界，YYYY/MM/DD 展示）
    const todayJst = new Intl.DateTimeFormat('ja-JP', {
      timeZone: 'Asia/Tokyo',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).format(new Date())
    await expect(page.locator('.entry-photo-date input')).toHaveValue(todayJst)

    // 保存 → 成功页：最终管理号与预览一致（单写者场景）+ QR + 本日 1 件目
    await page.locator('button[type="submit"]').click()
    await expect(page.locator('.entry-success-code')).toBeVisible()
    await expect(page.locator('.entry-success-code')).toHaveText(/^HT[A-Z]\d+-A1X$/)
    await expect(page.locator('.entry-success-qr')).toBeVisible()
    await expect(page.locator('.entry-success-count')).toContainText('1')
    await expect(page.locator('.entry-success-guide')).toContainText('油性ペン')
    // 照片角标：在传（蓝）→ 送信しました（绿，真实上传出清后翻色）
    await expect(page.locator('.entry-success-upload.is-done')).toContainText(
      '写真1枚を送信しました',
      { timeout: 15_000 },
    )

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
