import { expect, test, type Page } from '@playwright/test'

import { decodeQr } from './qr'
import { TEST_JPEG } from './fixtures'

/**
 * M2-7 标签打印闭环：录入带图 → /print 自动加载今天 → 标签排版/计数/引导条
 * → jsQR 解码 canvas 断言「码内容=管理号」（7.7 直接证明）→ 尺寸切换+缩略图（/img 代理链）。
 * 计数断言用实际标签数（entry.spec 先跑、同日已有件），不写死数字。
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

/** 打印页共享断言助手见 ./qr.ts（entry.spec 10 件闭环同样使用）。 */

test.describe('label printing (desktop-chromium)', () => {
  test('item entered with photo reaches the print page: labels, count, guide bar, jsQR decode matching the management code, and size switch with thumbnails', async ({ page }) => {
    await login(page, 'editor')

    // 录一件带图（缩略图来自真实上传链路：压缩→Dexie→保存绑定→后端缩略图落盘）
    await page.goto('/entry')
    await page.locator('.van-field').first().click()
    await page.locator('.van-picker__confirm').click()
    await page.locator('input[type="file"][capture]').setInputFiles({
      name: 'photo.jpg',
      mimeType: 'image/jpeg',
      buffer: TEST_JPEG,
    })
    // 压缩在 Web Worker 内执行，负载下可能超默认 5s——与 entry.spec 同口径显式放宽
    await expect(page.locator('.entry-photo-count')).toHaveText('1/9', { timeout: 10_000 })
    await page.locator('.van-field input').nth(2).fill('1000')
    await page.locator('button[type="submit"]').click()
    await expect(page.locator('.entry-success-code')).toBeVisible()
    const itemCode = (await page.locator('.entry-success-code').textContent())?.trim()
    // 照片出清后 thumbUrl 才有值（打印列表首图缩略图路径）
    await expect(page.locator('.entry-success-upload.is-done')).toContainText(
      '写真1枚を送信しました',
      { timeout: 15_000 },
    )

    // 打印页：今天区间自动加载（entry.spec 先跑、同日已有件——计数按实际标签数断言）
    await page.goto('/print')
    await expect(page).toHaveTitle('ラベル印刷｜在庫管理システム')
    await expect(page.locator('.print-count')).toBeVisible({ timeout: 10_000 })
    const labelCount = await page.locator('.print-label').count()
    expect(labelCount).toBeGreaterThanOrEqual(1)
    // 少量件数恒为 1 枚（65 面/枚规格）
    await expect(page.locator('.print-count')).toContainText(`・1枚`)
    // 人读码在场（QR 破损手输兜底的前提）+ 打印设置引导条
    await expect(page.locator('.print-label').last()).toContainText(itemCode!)
    await expect(page.locator('.print-guide')).toContainText('倍率100%')

    // jsQR 解码全部标签：本件管理号必在解码结果中（7.7「码内容恒为管理号」）
    const decoded: (string | null)[] = []
    for (let i = 0; i < labelCount; i++) {
      decoded.push(await decodeQr(page, i))
    }
    expect(decoded, `解码结果: ${decoded.join(', ')}`).toContain(itemCode!)
    expect(decoded.every((value) => value !== null)).toBe(true)

    // 尺寸切换 50×30（缩略图支持档）→ 打开缩略图开关 → 经 /img 代理加载首图缩略图
    await page.locator('.el-radio-button', { hasText: '50×30（36面）' }).click()
    await expect(page.locator('.el-switch')).not.toHaveClass(/is-disabled/)
    await page.locator('.el-switch').click()
    // 缩略图只对带图件渲染：同日件未必有照片（entry.spec 十件闭环不拍照），
    // 断言锚定本用例已知带图件——标签出现 /img/thumb/ 且真实加载成功（404 不算过）
    const ownThumb = page.locator('.print-label', { hasText: itemCode! }).locator('.print-thumb')
    await expect(ownThumb).toHaveCount(1)
    await expect(ownThumb).toHaveAttribute('src', /\/img\/thumb\//)
    await expect
      .poll(async () => ownThumb.evaluate((img: HTMLImageElement) => img.naturalWidth))
      .toBeGreaterThan(0)
  })

  test('viewer can also print (printing is an all-roles capability since entry and labeling hands often differ)', async ({ page }) => {
    await login(page, 'viewer')
    await page.goto('/print')
    // 上一用例与 entry.spec 的今日件对 viewer 同样可加载
    await expect(page.locator('.print-count')).toBeVisible({ timeout: 10_000 })
    await expect(page.locator('.print-count')).toContainText('件・1枚')
  })
})
