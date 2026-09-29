import 'fake-indexeddb/auto'
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest'

const apiMocks = vi.hoisted(() => ({ scanStocktakeItem: vi.fn() }))

vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return { ...actual, scanStocktakeItem: apiMocks.scanStocktakeItem }
})

import { db, type ScanActionEntry } from '@/db/dexie'
import { useScanQueue } from './useScanQueue'
import { ApiError } from '@/utils/api'

/**
 * 盘点扫码离线小队列（M6-②）：网络失败照记本地、恢复按序回放、
 * 幂等 200 出清、业务拒绝落 failures 不无限重试。
 */

const queue = useScanQueue()

/** 回放只关心成败不读载荷——夹具最小形状。 */
function scanResult(code: string) {
  return { repeated: false, item: { id: 1, itemCode: code }, thumbUrl: null }
}

/** fake-indexeddb 操作走宏任务——计数断言一律轮询等落账。 */
async function seedScan(stocktakeId: number, code: string, createdAt = Date.now()): Promise<void> {
  await db.scanActions.add({ stocktakeId, code, createdAt } satisfies ScanActionEntry)
}

beforeEach(async () => {
  vi.clearAllMocks()
  apiMocks.scanStocktakeItem.mockReset()
  await queue.resetForTests()
})

afterAll(async () => {
  await queue.resetForTests()
})

describe('enqueue: stores offline scans and counts per stocktake', () => {
  it('persists the entry and exposes per-stocktake and total counts', async () => {
    await queue.enqueue(7, 'HT9-A1X')
    await queue.enqueue(7, 'HT9-A2X')
    await queue.enqueue(8, 'HT9-B1X')

    await vi.waitFor(() => {
      expect(queue.pendingFor(7)).toBe(2)
      expect(queue.pendingFor(8)).toBe(1)
      expect(queue.state.pendingCount).toBe(3)
    })
    // 回放未触发（离线语义：enqueue 不自动冲）
    expect(apiMocks.scanStocktakeItem).not.toHaveBeenCalled()
  })
})

describe('flush: ordered replay with idempotent-200 clearing', () => {
  it('replays FIFO, deletes on success (including repeated=true), and refreshes counts', async () => {
    await seedScan(7, 'HT9-A1X', 1_000)
    await seedScan(7, 'HT9-A2X', 2_000)
    apiMocks.scanStocktakeItem
      .mockResolvedValueOnce({ ...scanResult('HT9-A1X'), repeated: true })
      .mockResolvedValueOnce(scanResult('HT9-A2X'))

    await queue.flush()

    // 按扫描序回放（先 A1 后 A2）
    expect(apiMocks.scanStocktakeItem).toHaveBeenNthCalledWith(1, 7, 'HT9-A1X')
    expect(apiMocks.scanStocktakeItem).toHaveBeenNthCalledWith(2, 7, 'HT9-A2X')
    expect(await db.scanActions.count()).toBe(0)
    await vi.waitFor(() => {
      expect(queue.state.pendingCount).toBe(0)
      expect(queue.pendingFor(7)).toBe(0)
    })
  })

  it('stops at the first network error and keeps the remaining entries ordered', async () => {
    await seedScan(7, 'HT9-A1X', 1_000)
    await seedScan(7, 'HT9-A2X', 2_000)
    await seedScan(7, 'HT9-A3X', 3_000)
    apiMocks.scanStocktakeItem.mockResolvedValueOnce(scanResult('HT9-A1X'))
    apiMocks.scanStocktakeItem.mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR'))

    await queue.flush()

    expect(apiMocks.scanStocktakeItem).toHaveBeenCalledTimes(2)
    // A1 出清，A2/A3 原序保留
    const remaining = await db.scanActions.orderBy('createdAt').toArray()
    expect(remaining.map((entry) => entry.code)).toEqual(['HT9-A2X', 'HT9-A3X'])
    await vi.waitFor(() => expect(queue.pendingFor(7)).toBe(2))
  })

  it('drops business rejections into failures instead of retrying forever', async () => {
    await seedScan(7, 'HT9-VOID', 1_000)
    await seedScan(7, 'HT9-OK', 2_000)
    apiMocks.scanStocktakeItem
      .mockRejectedValueOnce(new ApiError(404001, 'item not found'))
      .mockResolvedValueOnce(scanResult('HT9-OK'))

    await queue.flush()

    expect(await db.scanActions.count()).toBe(0)
    expect(queue.state.failures).toHaveLength(1)
    expect(queue.state.failures[0]).toMatchObject({ stocktakeId: 7, code: 'HT9-VOID' })
    expect(apiMocks.scanStocktakeItem).toHaveBeenCalledTimes(2)
  })

  it('is reentrant-safe: a second concurrent call returns without starting a second replay', async () => {
    await seedScan(7, 'HT9-A1X')
    let release: (() => void) | undefined
    apiMocks.scanStocktakeItem.mockImplementationOnce(
      () => new Promise((resolve) => {
        release = () => resolve(scanResult('HT9-A1X'))
      }),
    )

    const first = queue.flush()
    const second = queue.flush() // flushing=true → 立即返回，不起第二轮回放
    // 等 mock 真正进入在途（IDB 读是异步边界，release 需在 mock 调用后才存在）
    await vi.waitFor(() => expect(apiMocks.scanStocktakeItem).toHaveBeenCalledTimes(1))
    release?.()
    await Promise.all([first, second])

    expect(apiMocks.scanStocktakeItem).toHaveBeenCalledTimes(1)
    expect(await db.scanActions.count()).toBe(0)
  })
})

describe('init: restores counts and replays leftovers on reopen', () => {
  it('flushes entries persisted by a previous session', async () => {
    await seedScan(7, 'HT9-A1X')
    apiMocks.scanStocktakeItem.mockResolvedValue(scanResult('HT9-A1X'))

    await queue.init()
    await vi.waitFor(() => expect(apiMocks.scanStocktakeItem).toHaveBeenCalledTimes(1))
    await vi.waitFor(() => expect(queue.state.pendingCount).toBe(0))
  })
})
