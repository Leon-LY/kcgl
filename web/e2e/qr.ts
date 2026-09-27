import type { Page } from '@playwright/test'
import jsQR from 'jsqr'

/**
 * 浏览器内把 QR img 画进 canvas 取像素，Node 侧 jsQR 解码
 * （7.7「码内容=管理号」的直接证明；print/entry spec 共用）。
 */
export async function decodeQr(page: Page, index: number): Promise<string | null> {
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
