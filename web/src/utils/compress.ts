import imageCompression from 'browser-image-compression'

/**
 * 图片压缩唯一出口（docs/01 7.5）：≤0.3MB / 最长边 1920px / JPEG，Web Worker 内执行
 * （主线程零卡顿，连续录入流畅）。规格常量导出供契约测试断言。
 */
export const IMAGE_COMPRESS_OPTIONS = {
  maxSizeMB: 0.3,
  maxWidthOrHeight: 1920,
  useWebWorker: true,
  fileType: 'image/jpeg',
} as const

export interface CompressedImage {
  data: ArrayBuffer
  mimeType: string
}

export async function compressImage(file: File): Promise<CompressedImage> {
  const compressed = await imageCompression(file, { ...IMAGE_COMPRESS_OPTIONS })
  return { data: await compressed.arrayBuffer(), mimeType: 'image/jpeg' }
}
