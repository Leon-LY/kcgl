import { reactive } from 'vue'
import { db } from '@/db/dexie'
import { ApiError, scanStocktakeItem } from '@/utils/api'

/**
 * 盘点扫码离线小队列（M6-②，docs/01 7.5/移动端节）：仓库深处信号差——
 * 盘点是「人走着扫几百件」的场景，离线即断不可接受。网络失败的扫码照记
 * Dexie（scan_actions），恢复后按扫描序回放；服务端同单同件幂等
 * （repeated=true 200）保证重复回放安全（与页面直传并发同理）。
 *
 * - 仅网络错误（ApiError code 0）排队/保留；业务拒绝（码不存在/单已关闭）
 *   重试同样请求无意义，落 failures 由界面呈现，不无限退避。
 * - online/visible 即冲（iOS 无 Background Sync，扫码回放不进 SW——
 *   会话页存活期与重开 init 双覆盖足够，扫码体量小无需 SW 兜底）。
 */

export interface ScanFailure {
  stocktakeId: number
  code: string
  message: string
  at: number
}

const state = reactive({
  /** 未回放扫码总数（跨单）。 */
  pendingCount: 0,
  /** 回放进行中（防重入——并发调用共享一次回放）。 */
  flushing: false,
  /** 回放期业务拒绝（4xx），逐条供页面警示。 */
  failures: [] as ScanFailure[],
})

/** 按单计数（会话页角标/close 拦截依据）。 */
const pendingByStocktake = reactive(new Map<number, number>())

let initialized = false

function isNetworkError(error: unknown): boolean {
  return error instanceof ApiError && error.code === 0
}

async function refreshCounts(): Promise<void> {
  const rows = await db.scanActions.toArray()
  state.pendingCount = rows.length
  const byStocktake = new Map<number, number>()
  for (const row of rows) {
    byStocktake.set(row.stocktakeId, (byStocktake.get(row.stocktakeId) ?? 0) + 1)
  }
  pendingByStocktake.clear()
  byStocktake.forEach((count, id) => pendingByStocktake.set(id, count))
}

/** 回放循环：FIFO 逐条；网络错误即停（保留本条与后续的序），成功即出清。 */
async function flush(): Promise<void> {
  if (state.flushing) {
    return
  }
  state.flushing = true
  try {
    for (;;) {
      const entry = await db.scanActions.orderBy('createdAt').first()
      if (entry == null) {
        return
      }
      try {
        await scanStocktakeItem(entry.stocktakeId, entry.code)
        // 200（含 repeated=true 幂等读回）即出清——重复回放与页面直传并发均安全
        await db.scanActions.delete(entry.id)
      } catch (error) {
        if (isNetworkError(error)) {
          return // 网络仍不可用：保留本条与后续
        }
        await db.scanActions.delete(entry.id)
        state.failures.push({
          stocktakeId: entry.stocktakeId,
          code: entry.code,
          message: error instanceof ApiError ? error.message : String(error),
          at: Date.now(),
        })
      }
    }
  } finally {
    state.flushing = false
    await refreshCounts()
  }
}

// ------------------------------------------------------------- 生命周期

function setupListeners(): void {
  window.addEventListener('online', () => {
    void flush()
  })
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      void flush()
    }
  })
}

// 模块加载即注册（幂等回调，队列空时无操作）；不挂在 init 上避免依赖调用次序
setupListeners()

async function init(): Promise<void> {
  if (initialized) {
    return
  }
  initialized = true
  try {
    await refreshCounts()
    // 重开即有存货（上个会话离线扫的）：立即冲一次
    if (state.pendingCount > 0) {
      void flush()
    }
  } catch {
    // IndexedDB 不可用（Safari 隐私模式等）：直连扫码路径仍在
  }
}

// ------------------------------------------------------------- 队列操作

/** 网络失败时照记（不自动冲——离线语义，等恢复信号）。 */
async function enqueue(stocktakeId: number, code: string): Promise<void> {
  await db.scanActions.add({ stocktakeId, code, createdAt: Date.now() })
  await refreshCounts()
}

function pendingFor(stocktakeId: number): number {
  return pendingByStocktake.get(stocktakeId) ?? 0
}

/** 页面「閉じる」：清掉本单的回放拒绝警示（条目本身已在 flush 中出清）。 */
function dismissFailures(stocktakeId: number): void {
  const kept = state.failures.filter((failure) => failure.stocktakeId !== stocktakeId)
  state.failures.splice(0, state.failures.length, ...kept)
}

/** 测试隔离：清表、复位状态。 */
async function resetForTests(): Promise<void> {
  initialized = false
  await db.scanActions.clear()
  state.pendingCount = 0
  state.flushing = false
  state.failures.splice(0, state.failures.length)
  pendingByStocktake.clear()
}

export function useScanQueue() {
  return {
    state,
    init,
    enqueue,
    flush: () => Promise.resolve(flush()),
    pendingFor,
    dismissFailures,
    resetForTests,
  }
}
