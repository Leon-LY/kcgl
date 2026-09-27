import { expect, test, type Page } from '@playwright/test'
import jsQR from 'jsqr'

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

/** 浏览器内把 QR img 画进 canvas 取像素，Node 侧 jsQR 解码（码内容=管理号的直接证明）。 */
async function decodeQr(page: Page, index: number): Promise<string | null> {
  const image = await page.locator('.print-qr').nth(index).evaluate((img: HTMLImageElement) => {
    const canvas = document.createElement('canvas')
    canvas.width = img.naturalWidth
    canvas.height = img.naturalHeight
    const ctx = canvas.getContext('2d')!
    ctx.drawImage(img, 0, 0)
    const data = ctx.getImageData(0, 0, canvas.width, canvas.height)
    return { width: data.width, height: data.height, pixels: Array.from(data.data) }
  })
  const code = jsQR(new Uint8ClampedArray(image.pixels), image.width, image.height)
  return code?.data ?? null
}

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
    await expect(page.locator('.entry-photo-count')).toHaveText('1/9')
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
    const thumbCount = await page.locator('.print-thumb').count()
    expect(thumbCount).toBe(labelCount)
    const thumbSrc = await page.locator('.print-thumb').first().getAttribute('src')
    expect(thumbSrc).toContain('/img/thumb/')
  })

  test('viewer can also print (printing is an all-roles capability since entry and labeling hands often differ)', async ({ page }) => {
    await login(page, 'viewer')
    await page.goto('/print')
    // 上一用例与 entry.spec 的今日件对 viewer 同样可加载
    await expect(page.locator('.print-count')).toBeVisible({ timeout: 10_000 })
    await expect(page.locator('.print-count')).toContainText('件・1枚')
  })
})
