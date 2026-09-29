import imageCompression from 'browser-image-compression'
// Worker 内经 importScripts(libURL) 取库：不传时库默认拉 cdn.jsdelivr.net——公网
// CDN 不可达/被拦时 importScripts 静默悬挂（无超时、异常才回退主线程），照片选择
// 无限等待。?url 让 Vite 把库的 UMD 构建作为带哈希同源资产下发，供给 Worker。
import browserImageCompressionUrl from 'browser-image-compression/dist/browser-image-compression.js?url'

/**
 * 图片压缩唯一出口（docs/01 7.5）：≤0.3MB / 最长边 1920px / JPEG，Web Worker 内执行
 * （主线程零卡顿，连续录入流畅）。规格常量导出供契约测试断言。
 */
export const IMAGE_COMPRESS_OPTIONS = {
  maxSizeMB: 0.3,
  maxWidthOrHeight: 1920,
  useWebWorker: true,
  fileType: 'image/jpeg',
  libURL: browserImageCompressionUrl,
} as const

export interface CompressedImage {
  data: ArrayBuffer
  mimeType: string
}

export async function compressImage(file: File): Promise<CompressedImage> {
  const compressed = await imageCompression(file, { ...IMAGE_COMPRESS_OPTIONS })
  return { data: await compressed.arrayBuffer(), mimeType: 'image/jpeg' }
}
