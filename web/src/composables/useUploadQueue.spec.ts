import 'fake-indexeddb/auto'
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest'

const apiMocks = vi.hoisted(() => ({ uploadImage: vi.fn() }))

// 压缩走透传桩（jsdom 无 canvas，压缩规格契约由 compress.spec 单独断言）
vi.mock('@/utils/compress', () => ({
  compressImage: async (file: File) => ({ data: await file.arrayBuffer(), mimeType: 'image/jpeg' }),
}))
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return { ...actual, uploadImage: apiMocks.uploadImage }
})

import { db, type UploadQueueEntry } from '@/db/dexie'
import { useUploadQueue } from './useUploadQueue'
import { ApiError, type ImageUploadResult } from '@/utils/api'

const queue = useUploadQueue()

function fileOf(name: string): File {
  return new File([new Uint8Array([0xff, 0xd8, 0xff, 0x01, 0x02])], name, { type: 'image/jpeg' })
}

function resultOf(clientUuid: string): ImageUploadResult {
  return {
    id: 1,
    clientUuid,
    itemId: 5,
    url: `/img/orig/2026/09/${clientUuid}.jpg`,
    thumbUrl: `/img/thumb/2026/09/${clientUuid}.jpg`,
    imageType: 1,
    sortOrder: 0,
  }
}

async function seedEntry(overrides: Partial<UploadQueueEntry>): Promise<UploadQueueEntry> {
  const entry: UploadQueueEntry = {
    clientUuid: crypto.randomUUID(),
    itemId: 5,
    data: new Uint8Array([1, 2, 3]).buffer,
    mimeType: 'image/jpeg',
    status: 'pending',
    attempts: 0,
    nextRetryAt: 0,
    createdAt: Date.now(),
    ...overrides,
  }
  await db.uploadQueue.add(entry)
  return entry
}

beforeEach(async () => {
  vi.clearAllMocks()
  apiMocks.uploadImage.mockReset()
  apiMocks.uploadImage.mockResolvedValue(resultOf('any'))
  await queue.resetForTests()
})

// 套件收尾清掉已武装的退避定时器：最后一个退避用例结束后 ~1s 定时器仍会触发，
// 对已 reset 的 mock 继续打调用并重挂新定时器，形成链式定时器拖住进程退出
afterAll(async () => {
  await queue.resetForTests()
})

describe('startup scan (zombie reset)', () => {
  it('resets stale uploading entries to pending and drains the queue', async () => {
    await seedEntry({ status: 'uploading' })
    await queue.init()
    await queue.whenIdle()

    expect(apiMocks.uploadImage).toHaveBeenCalledTimes(1)
    expect(await db.uploadQueue.count()).toBe(0)
  })
})

describe('addFiles: compresses and enqueues as pending_bind', () => {
  it('returns entries (UUID key / compressed data) stored as pending_bind with a null itemId', async () => {
    const created = await queue.addFiles([fileOf('a.jpg'), fileOf('b.jpg')])

    expect(created).toHaveLength(2)
    for (const entry of created) {
      expect(entry.clientUuid).toMatch(/^[0-9a-f-]{36}$/)
      expect(entry.status).toBe('pending_bind')
      expect(entry.itemId).toBeNull()
      expect(new Uint8Array(entry.data)).toEqual(new Uint8Array([0xff, 0xd8, 0xff, 0x01, 0x02]))
    }
    expect(queue.state.unboundCount).toBe(2)
    expect(queue.state.waitingCount).toBe(0)
    // 未绑定不上传
    expect(apiMocks.uploadImage).not.toHaveBeenCalled()
  })
})

describe('bindItem: binds the item and drains the queue (queue side of the idempotent-200 contract)', () => {
  it('pending_bind → pending → deletes the row on upload success and zeroes the counts', async () => {
    const created = await queue.addFiles([fileOf('a.jpg')])
    const bound = await queue.bindItem(5)

    expect(bound).toBe(1)
    await queue.whenIdle()

    expect(apiMocks.uploadImage).toHaveBeenCalledTimes(1)
    const form = apiMocks.uploadImage.mock.calls[0]![0] as FormData
    expect(form.get('clientUuid')).toBe(created[0]!.clientUuid)
    expect(form.get('itemId')).toBe('5')
    expect(await db.uploadQueue.count()).toBe(0)
    expect(queue.state.waitingCount).toBe(0)
    expect(queue.state.unboundCount).toBe(0)
    expect(queue.activeByItem.get(5)).toBeUndefined()
  })

  it('removeUnbound deletes unbound photos (form × button)', async () => {
    const created = await queue.addFiles([fileOf('a.jpg')])
    await queue.removeUnbound(created[0]!.clientUuid)

    expect(await db.uploadQueue.count()).toBe(0)
    expect(queue.state.unboundCount).toBe(0)
  })
})

describe('ordering within a single item (two concurrent lanes never interleave one item)', () => {
  it('holds a same-item photo until the previous one finishes', async () => {
    // 直接播种 3 张就绪条目（绕过 bindItem 的自动泵），受控 mock 挂好后手动启泵——
    // 不能 await pump()：它会等到受控 Promise 释放才返回，先等第一张在传中再放行
    await seedEntry({ clientUuid: 'e1', createdAt: Date.now() - 3 })
    await seedEntry({ clientUuid: 'e2', createdAt: Date.now() - 2 })
    await seedEntry({ clientUuid: 'e3', createdAt: Date.now() - 1 })

    let releaseFirst!: (value: ImageUploadResult) => void
    apiMocks.uploadImage.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          releaseFirst = resolve
        }),
    )
    void queue.pump()
    // 第一张在传中（受控悬置）：同 item 的第二三张被 claimedItems 挡住；
    // 若串行化失效，二三张会立即走基础 mock 完成 → 此窗口内即见 3 次调用
    await new Promise((resolve) => setTimeout(resolve, 120))
    expect(apiMocks.uploadImage).toHaveBeenCalledTimes(1)

    releaseFirst(resultOf('first'))
    await queue.whenIdle()
    expect(apiMocks.uploadImage).toHaveBeenCalledTimes(3)
    expect(await db.uploadQueue.count()).toBe(0)
  })
})

describe('backoff retry (1s→5s→30s→5min cap + jitter)', () => {
  it('on network failure: stays pending with a future nextRetryAt, then auto-retries successfully when the timer fires', async () => {
    apiMocks.uploadImage
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR'))
      .mockResolvedValueOnce(resultOf('ok'))

    await queue.addFiles([fileOf('a.jpg')])
    await queue.bindItem(5)
    await queue.whenIdle() // 首败后进入退避等待

    expect(apiMocks.uploadImage).toHaveBeenCalledTimes(1)
    const row = await db.uploadQueue.toArray()
    expect(row[0]!.attempts).toBe(1)
    expect(row[0]!.nextRetryAt).toBeGreaterThan(Date.now()) // 1s 档 + jitter
    expect(row[0]!.nextRetryAt - Date.now()).toBeLessThan(1400)
    expect(queue.state.waitingCount).toBe(1)

    // 退避到点（1s+jitter ≤1.3s）后重试计时器自动再启泵
    await new Promise((resolve) => setTimeout(resolve, 1500))
    await queue.whenIdle()

    expect(apiMocks.uploadImage).toHaveBeenCalledTimes(2)
    expect(await db.uploadQueue.count()).toBe(0)
  }, 10_000)

  it('online event clears the backoff clock immediately and resumes uploading', async () => {
    apiMocks.uploadImage
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR'))
      .mockResolvedValueOnce(resultOf('ok'))

    await queue.addFiles([fileOf('a.jpg')])
    await queue.bindItem(5)
    await queue.whenIdle()
    expect(apiMocks.uploadImage).toHaveBeenCalledTimes(1)

    window.dispatchEvent(new Event('online'))
    await queue.whenIdle()

    expect(apiMocks.uploadImage).toHaveBeenCalledTimes(2)
    expect(await db.uploadQueue.count()).toBe(0)
  })

  it('visibilitychange → visible triggers a catch-up upload (iOS reopen)', async () => {
    // 直接种一条就绪条目（不经 bindItem 的自动泵），仅由可见性事件唤醒
    await seedEntry({})
    document.dispatchEvent(new Event('visibilitychange'))
    await queue.whenIdle()

    expect(apiMocks.uploadImage).toHaveBeenCalledTimes(1)
    expect(await db.uploadQueue.count()).toBe(0)
  })
})

describe('permanent failure (4xx business rejection / 507 disk full): no endless backoff', () => {
  it('400008 → deletes the row and records a failure grouped by item, never retries', async () => {
    apiMocks.uploadImage.mockRejectedValue(new ApiError(400008, '商品画像は1件につき9枚までです'))

    await queue.addFiles([fileOf('a.jpg')])
    await queue.bindItem(5)
    await queue.whenIdle()
    await new Promise((resolve) => setTimeout(resolve, 100))

    expect(apiMocks.uploadImage).toHaveBeenCalledTimes(1) // 无第二次
    expect(await db.uploadQueue.count()).toBe(0) // 行已删
    expect(queue.state.failures).toHaveLength(1)
    expect(queue.state.failures[0]).toMatchObject({ itemId: 5, code: 400008 })
  })

  it('500000 system error → backs off like a network error (retryable)', async () => {
    apiMocks.uploadImage.mockRejectedValue(new ApiError(500000, 'システムエラー', 'e-1'))

    await queue.addFiles([fileOf('a.jpg')])
    await queue.bindItem(5)
    await queue.whenIdle()

    expect(apiMocks.uploadImage).toHaveBeenCalledTimes(1)
    expect(await db.uploadQueue.count()).toBe(1) // 留在队列退避
    expect(queue.state.failures).toHaveLength(0)
  })
})
