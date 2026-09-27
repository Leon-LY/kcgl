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

describe('启动扫描（H3：僵尸复位）', () => {
  it('遗留 uploading 复位 pending 并继续上传出清', async () => {
    await seedEntry({ status: 'uploading' })
    await queue.init()
    await queue.whenIdle()

    expect(apiMocks.uploadImage).toHaveBeenCalledTimes(1)
    expect(await db.uploadQueue.count()).toBe(0)
  })
})

describe('addFiles：压缩入队 pending_bind', () => {
  it('返回条目（UUID 键/压缩数据），库里 pending_bind 且 itemId 为空', async () => {
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

describe('bindItem：绑定商品并上传出清（幂等 200 契约的队列侧）', () => {
  it('pending_bind → pending → 上传成功删行、计数归零', async () => {
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

  it('removeUnbound 删除未绑定照片（表单 × 按钮）', async () => {
    const created = await queue.addFiles([fileOf('a.jpg')])
    await queue.removeUnbound(created[0]!.clientUuid)

    expect(await db.uploadQueue.count()).toBe(0)
    expect(queue.state.unboundCount).toBe(0)
  })
})

describe('单 item 内保序（并发 2 路不交错同一商品）', () => {
  it('同 item 第二张等第一张完成后才开始', async () => {
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

describe('退避重试（1s→5s→30s→5min 封顶 +jitter）', () => {
  it('网络失败 → pending + nextRetryAt 未来 → 定时到点自动重试成功', async () => {
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

  it('online 事件立即清零退避时钟并续传（弱网恢复即冲）', async () => {
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

  it('visibilitychange → visible 触发补传（iOS 打开即补传）', async () => {
    // 直接种一条就绪条目（不经 bindItem 的自动泵），仅由可见性事件唤醒
    await seedEntry({})
    document.dispatchEvent(new Event('visibilitychange'))
    await queue.whenIdle()

    expect(apiMocks.uploadImage).toHaveBeenCalledTimes(1)
    expect(await db.uploadQueue.count()).toBe(0)
  })
})

describe('永久失败（4xx 业务拒绝 / 507 磁盘满）：不无限退避', () => {
  it('400008 → 删行并落 failures（按 item 归属），不重试', async () => {
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

  it('500000 系统错误 → 按网络错误退避（可重试）', async () => {
    apiMocks.uploadImage.mockRejectedValue(new ApiError(500000, 'システムエラー', 'e-1'))

    await queue.addFiles([fileOf('a.jpg')])
    await queue.bindItem(5)
    await queue.whenIdle()

    expect(apiMocks.uploadImage).toHaveBeenCalledTimes(1)
    expect(await db.uploadQueue.count()).toBe(1) // 留在队列退避
    expect(queue.state.failures).toHaveLength(0)
  })
})
