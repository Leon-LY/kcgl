import { expect, test, type Page } from '@playwright/test'
import { TEST_JPEG } from './fixtures'

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

test.describe('continuous entry (desktop-chromium)', () => {
  test('entering one item succeeds: full-width price normalization, code preview, and final management code/QR/daily count', async ({ page }) => {
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

  test('viewer navigating straight to /entry is bounced home by the route guard (frontend guard on top of the server-side 403)', async ({ page }) => {
    await login(page, 'viewer')
    await page.goto('/entry')
    await expect(page).toHaveURL(/\/$/)
    await expect(page.locator('.home-welcome')).toBeVisible()
  })

  test('void and re-enter full chain: required cancellation reason, prefilled fields with inherited photos, and a new code that differs from the old one', async ({ page }) => {
    await login(page, 'editor')
    await page.goto('/entry')

    // 录一件带照片（照片须上传完成——重录的服务端复制以旧件已落库的图片行为准）
    await page.locator('.van-field').first().click()
    await page.locator('.van-picker__confirm').click()
    await page.locator('input[type="file"][capture]').setInputFiles({
      name: 'photo.jpg',
      mimeType: 'image/jpeg',
      buffer: TEST_JPEG,
    })
    await expect(page.locator('.entry-photo-count')).toHaveText('1/9')
    await page.locator('.van-field input').nth(2).fill('1000')
    await page.locator('button[type="submit"]').click()
    await expect(page.locator('.entry-success-code')).toBeVisible()
    const oldCode = await page.locator('.entry-success-code').textContent()
    await expect(page.locator('.entry-success-upload.is-done')).toContainText(
      '写真1枚を送信しました',
      { timeout: 15_000 },
    )

    // 成功页取り消して再登録：理由必填（空理由被拦）→ 填写 → 确认
    await page.getByRole('button', { name: '取り消して再登録' }).click()
    await expect(page.locator('.entry-void')).toBeVisible()
    await page.getByRole('button', { name: '取り消して再入力へ' }).click()
    await expect(page.locator('.entry-void-error')).toContainText('取り消し理由を入力してください')

    await page.locator('.entry-void textarea').fill('価格入力ミス')
    await page.getByRole('button', { name: '取り消して再入力へ' }).click()

    // 重录表单：横幅旧号 + 继承图片（服务端复制，无需重拍）+ 单价预填
    await expect(page.locator('.entry-reentry')).toBeVisible()
    await expect(page.locator('.entry-reentry')).toContainText(oldCode!)
    await expect(page.locator('.entry-inherited-photos img')).toHaveCount(1)
    await expect(page.locator('.van-field input').nth(2)).toHaveValue('1000')

    // 改价（纠错场景）→ 保存 → 新号 ≠ 旧号、本日 2 件目
    await page.locator('.van-field input').nth(2).fill('2000')
    await page.locator('button[type="submit"]').click()
    await expect(page.locator('.entry-success-code')).toBeVisible({ timeout: 10_000 })
    await expect(page.locator('.entry-success-code')).not.toHaveText(oldCode!)
    await expect(page.locator('.entry-success-count')).toContainText('2')
  })
})
