import { describe, expect, it, vi } from 'vitest'

const libMock = vi.hoisted(() => ({ compress: vi.fn() }))

// jsdom 无 canvas/Worker：库调用桩化，仅断言「规格契约原样透传 + 返回形态」；
// 压缩产物 ≤0.3MB/1920px 由 browser-image-compression 按 maxSizeMB/maxWidthOrHeight 保证
vi.mock('browser-image-compression', () => ({
  default: (file: File, options: unknown) => libMock.compress(file, options),
}))

import { compressImage, IMAGE_COMPRESS_OPTIONS } from './compress'

describe('compression spec contract (docs/01 7.5: ≤0.3MB / 1920px / JPEG / Web Worker)', () => {
  it('keeps spec constants aligned with the documented values', () => {
    expect(IMAGE_COMPRESS_OPTIONS.maxSizeMB).toBe(0.3)
    expect(IMAGE_COMPRESS_OPTIONS.maxWidthOrHeight).toBe(1920)
    expect(IMAGE_COMPRESS_OPTIONS.fileType).toBe('image/jpeg')
    expect(IMAGE_COMPRESS_OPTIONS.useWebWorker).toBe(true)
  })

  it('passes the spec through in compressImage and returns ArrayBuffer + mime', async () => {
    const file = new File([new Uint8Array([1, 2, 3])], 'a.jpg', { type: 'image/jpeg' })
    libMock.compress.mockResolvedValue(
      new File([new Uint8Array([9, 9])], 'a.jpg', { type: 'image/jpeg' }),
    )

    const out = await compressImage(file)

    expect(libMock.compress).toHaveBeenCalledTimes(1)
    expect(libMock.compress).toHaveBeenCalledWith(
      file,
      expect.objectContaining({ maxSizeMB: 0.3, maxWidthOrHeight: 1920, fileType: 'image/jpeg' }),
    )
    expect(out.mimeType).toBe('image/jpeg')
    expect(out.data).toBeInstanceOf(ArrayBuffer)
    expect(new Uint8Array(out.data)).toEqual(new Uint8Array([9, 9]))
  })

  it('rethrows library failures (caller prompts for a retake inline)', async () => {
    libMock.compress.mockRejectedValue(new Error('not an image'))
    await expect(compressImage(new File([], 'x.txt'))).rejects.toThrow('not an image')
  })
})
