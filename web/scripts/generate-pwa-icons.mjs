import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import QRCode from 'qrcode'

/**
 * PWA/Apple 图标生成（M6-①）：QR 码做应用图标——扫码是本系统的核心动词，
 * 图标即语义。复用运行时已有的 qrcode 依赖，不引入图像库。
 *
 * 配色对齐 brand.css：主蓝 #2062a6。
 * - 常规图标（purpose any）：蓝码白底，launcher 自行裁形。
 * - maskable：白码蓝底 + 加大边距（中央 80% 安全区；蓝色即背景色，
 *   被圆形裁切后仍是完整品牌底）。
 * - apple-touch-icon（180/167/152）：iOS 不读 manifest icons，index.html
 *   显式 <link> 三尺寸；白码蓝底同 maskable 观感。
 *
 * 幂等可重跑：node scripts/generate-pwa-icons.mjs
 */

const BRAND_BLUE = '#2062a6'
const WHITE = '#ffffff'
const OUT_DIR = fileURLToPath(new URL('../public/icons/', import.meta.url))

const TARGETS = [
  { file: 'pwa-192.png', size: 192, dark: BRAND_BLUE, light: WHITE, margin: 4 },
  { file: 'pwa-512.png', size: 512, dark: BRAND_BLUE, light: WHITE, margin: 4 },
  { file: 'pwa-512-maskable.png', size: 512, dark: WHITE, light: BRAND_BLUE, margin: 8 },
  { file: 'apple-touch-icon.png', size: 180, dark: WHITE, light: BRAND_BLUE, margin: 6 },
  { file: 'apple-touch-icon-167.png', size: 167, dark: WHITE, light: BRAND_BLUE, margin: 6 },
  { file: 'apple-touch-icon-152.png', size: 152, dark: WHITE, light: BRAND_BLUE, margin: 6 },
]

await mkdir(OUT_DIR, { recursive: true })
for (const target of TARGETS) {
  const buffer = await QRCode.toBuffer('KCGL', {
    type: 'png',
    width: target.size,
    margin: target.margin,
    errorCorrectionLevel: 'M',
    color: { dark: target.dark, light: target.light },
  })
  await writeFile(`${OUT_DIR}${target.file}`, buffer)
  console.log(`generated ${target.file} (${target.size}x${target.size})`)
}
