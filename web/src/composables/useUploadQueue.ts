import { reactive } from 'vue'
import { db, type UploadQueueEntry } from '@/db/dexie'
import { ApiError, uploadImage } from '@/utils/api'
import { compressImage } from '@/utils/compress'
import { newClientId } from '@/utils/id'

/**
 * 图片上传队列（docs/01 7.5 唯一定义）：Dexie 持久化 + 幂等 200 出清 + 退避重试。
 *
 * - 先存后传：选图即压缩落 IndexedDB（pending_bind），文字保存拿到 itemId 后 bindItem
 *   转正式队列开始上传——杀进程/刷新不丢，弱网恢复自动续传。
 * - 幂等契约（7.0）：clientUuid 重放服务端 200 读回，队列据此出清；网络错误退避
 *   1s→5s→30s→5min 封顶 +jitter，online 事件立即清零重试。
 * - 并发 2 路、单 item 内保序（claimedItems 去重）；启动把遗留 uploading 复位 pending
 *   （僵尸队头不阻塞——「杀进程不丢」的反面是「传不出」）。
 * - 4xx 业务拒绝（magic/超限/9 图上限）与 507 磁盘满：重试同样字节无意义，落 failures
 *   由界面呈现，不无限退避。
 */

/** 退避阶梯（attempts 从 1 起）：1s → 5s → 30s → 5min 封顶。 */
const BACKOFF_STEPS_MS = [1_000, 5_000, 30_000, 300_000]
const JITTER_RATIO = 0.3
/** 并发上传路数（单 item 内保序，跨 item 并行）。 */
const CONCURRENCY = 2

export interface UploadFailure {
  clientUuid: string
  itemId: number
  code: number
  message: string
  at: number
}

const state = reactive({
  /** 已绑定商品的待传数（含退避等待中）。 */
  waitingCount: 0,
  uploadingCount: 0,
  /** 未保存商品的孤儿照片数（pending_bind）。 */
  unboundCount: 0,
  failures: [] as UploadFailure[],
})

/** 各 item 的活跃上传数（pending+uploading；reactive Map，页面按 item 显示角标）。 */
const activeByItem = reactive(new Map<number, number>())

let initialized = false
let pumping = false
let activeUploads = 0
let retryTimer: ReturnType<typeof setTimeout> | null = null
const claimedItems = new Set<number>()

const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms))

function backoffMs(attempts: number): number {
  const step = Math.min(attempts, BACKOFF_STEPS_MS.length) - 1
  const base = BACKOFF_STEPS_MS[step]
  return Math.round(base + base * JITTER_RATIO * Math.random())
}

function isPermanentFailure(error: unknown): boolean {
  if (!(error instanceof ApiError)) {
    return false
  }
  if (error.code >= 400000 && error.code < 500000) {
    return true // 业务拒绝：重试同样字节不会变结果
  }
  return Math.floor(error.code / 1000) === 507 // DISK_FULL：退避无意义，等管理员
}

async function refreshCounts(): Promise<void> {
  const rows = await db.uploadQueue.toArray()
  let waiting = 0
  let uploading = 0
  let unbound = 0
  const byItem = new Map<number, number>()
  for (const row of rows) {
    if (row.status === 'pending_bind') {
      unbound++
    } else if (row.itemId != null) {
      if (row.status === 'uploading') {
        uploading++
      } else {
        waiting++
      }
      byItem.set(row.itemId, (byItem.get(row.itemId) ?? 0) + 1)
    }
  }
  state.waitingCount = waiting
  state.uploadingCount = uploading
  state.unboundCount = unbound
  activeByItem.clear()
  byItem.forEach((count, itemId) => activeByItem.set(itemId, count))
}

// ------------------------------------------------------------------ 上传循环

async function takeNext(): Promise<UploadQueueEntry | null> {
  const now = Date.now()
  const candidates = await db.uploadQueue
    .where('status')
    .equals('pending')
    .and((entry) => entry.itemId != null && entry.nextRetryAt <= now)
    .sortBy('createdAt')
  return candidates.find((entry) => !claimedItems.has(entry.itemId as number)) ?? null
}

async function uploadOne(entry: UploadQueueEntry): Promise<void> {
  const itemId = entry.itemId as number
  await db.uploadQueue.update(entry.clientUuid, { status: 'uploading' })
  await refreshCounts()
  activeUploads++
  const form = new FormData()
  form.append('file', new Blob([entry.data], { type: entry.mimeType }), `${entry.clientUuid}.jpg`)
  form.append('clientUuid', entry.clientUuid)
  form.append('itemId', String(itemId))
  form.append('imageType', '1')
  try {
    await uploadImage(form)
    await db.uploadQueue.delete(entry.clientUuid)
  } catch (error) {
    if (isPermanentFailure(error)) {
      await db.uploadQueue.delete(entry.clientUuid)
      const apiError = error instanceof ApiError ? error : null
      state.failures.push({
        clientUuid: entry.clientUuid,
        itemId,
        code: apiError?.code ?? 0,
        message: apiError?.message ?? String(error),
        at: Date.now(),
      })
    } else {
      const attempts = entry.attempts + 1
      await db.uploadQueue.update(entry.clientUuid, {
        status: 'pending',
        attempts,
        nextRetryAt: Date.now() + backoffMs(attempts),
      })
      // 退避等待期间页面可能被关闭——重新挂 Background Sync 让 SW 兜底回放
      void registerBackgroundSync()
    }
  } finally {
    activeUploads--
  }
  await refreshCounts()
}

async function drainWorker(): Promise<void> {
  for (;;) {
    const entry = await takeNext()
    if (entry == null) {
      return
    }
    claimedItems.add(entry.itemId as number)
    try {
      await uploadOne(entry)
    } finally {
      claimedItems.delete(entry.itemId as number)
    }
  }
}

async function pump(): Promise<void> {
  if (pumping) {
    return
  }
  pumping = true
  try {
    await Promise.all(Array.from({ length: CONCURRENCY }, () => drainWorker()))
  } finally {
    pumping = false
    await scheduleRetry()
  }
}

/** 无可传条目但有退避等待时，按最早 nextRetryAt 定时再启。 */
async function scheduleRetry(): Promise<void> {
  if (retryTimer != null) {
    return
  }
  const waiting = await db.uploadQueue
    .where('status')
    .equals('pending')
    .and((entry) => entry.itemId != null)
    .toArray()
  if (waiting.length === 0) {
    return
  }
  const earliest = Math.min(...waiting.map((entry) => entry.nextRetryAt))
  const delay = Math.max(0, earliest - Date.now())
  retryTimer = setTimeout(() => {
    retryTimer = null
    void pump()
  }, delay)
}

// ------------------------------------------------------------------ 生命周期

/** online：立即清零重试时钟（弱网恢复即冲）；visibilitychange visible：iOS 打开即补传。 */
function setupListeners(): void {
  window.addEventListener('online', () => {
    void db.uploadQueue
      .where('status')
      .equals('pending')
      .modify({ nextRetryAt: 0 })
      .then(() => pump())
  })
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      void pump()
    }
  })
  // iOS 无 Background Sync——关页/切走时队列仍有存货则留警示（docs/01 R2 四件套）
  window.addEventListener('beforeunload', (event) => {
    if (state.waitingCount + state.uploadingCount + state.unboundCount > 0) {
      event.preventDefault()
      // Chrome 需要 returnValue 非空才弹确认框（规范遗留字段）
      event.returnValue = ''
    }
  })
  // SW Background Sync 回放成功后通知：刷新计数并继续泵（清剩余退避条目）
  navigator.serviceWorker?.addEventListener('message', (event) => {
    if ((event as MessageEvent).data?.type === 'kcgl-upload-replayed') {
      void refreshCounts().then(() => pump())
    }
  })
}

/** 安卓主路径（docs/01 R2）：注册一次性 Background Sync——页面关闭后网络恢复时
 *  SW 直接回放（见 src/sw.ts）。不可用（iOS/Safari 等）静默降级到页面存活循环。 */
async function registerBackgroundSync(): Promise<void> {
  try {
    if (!('serviceWorker' in navigator)) {
      return
    }
    const registration = await navigator.serviceWorker.ready
    const sync = (
      registration as ServiceWorkerRegistration & {
        sync?: { register: (tag: string) => Promise<void> }
      }
    ).sync
    if (sync != null) {
      await sync.register('kcgl-upload-queue')
    }
  } catch {
    // 注册失败不阻断上传——页面泵仍是兜底
  }
}

// 模块加载即注册（幂等回调，队列空时无操作）；不挂在 init 上避免「监听是否存在」依赖调用次序
setupListeners()

async function init(): Promise<void> {
  if (initialized) {
    return
  }
  initialized = true
  try {
    // 僵尸复位：上次进程遗留的 uploading → pending
    await db.uploadQueue.where('status').equals('uploading').modify({ status: 'pending' })
    await refreshCounts()
    // 重开即有存货：挂上 Background Sync（此前会话可能没等到网络恢复就被关闭）
    if (state.waitingCount > 0) {
      void registerBackgroundSync()
    }
    void pump()
  } catch {
    // IndexedDB 不可用（Safari 隐私模式等）：队列静默降级——直传路径仍在
  }
}

// ------------------------------------------------------------------ 队列操作

/** 压缩并落库（pending_bind）。返回新建条目（含压缩后数据，供调用方做本地预览）。 */
async function addFiles(files: File[]): Promise<UploadQueueEntry[]> {
  // 存储保险（docs/01 7.5）：有照片在本地即申请持久化——ITP/配额压力下多一道防线
  navigator.storage?.persist?.().catch(() => undefined)
  const created: UploadQueueEntry[] = []
  for (const file of files) {
    const { data, mimeType } = await compressImage(file)
    created.push({
      clientUuid: newClientId(),
      itemId: null,
      data,
      mimeType,
      status: 'pending_bind',
      attempts: 0,
      nextRetryAt: 0,
      createdAt: Date.now(),
    })
  }
  if (created.length > 0) {
    await db.uploadQueue.bulkAdd(created)
    await refreshCounts()
  }
  return created
}

/** 文字保存成功后绑定商品：全部 pending_bind → pending（单用户会话语义）。返回绑定数。 */
async function bindItem(itemId: number): Promise<number> {
  await db.uploadQueue.where('status').equals('pending_bind').modify({ status: 'pending', itemId })
  const bound = await db.uploadQueue.where('itemId').equals(itemId).count()
  await refreshCounts()
  void registerBackgroundSync()
  void pump()
  return bound
}

/** 删除一张未绑定照片（表单上的 × 按钮）。 */
async function removeUnbound(clientUuid: string): Promise<void> {
  await db.uploadQueue.delete(clientUuid)
  await refreshCounts()
}

/** 无事可做。测试与「等队列清空」场景用：无在传、无到期待传、泵已停即返回。 */
async function whenIdle(): Promise<void> {
  for (;;) {
    const now = Date.now()
    const ready = await db.uploadQueue
      .where('status')
      .equals('pending')
      .and((entry) => entry.itemId != null && entry.nextRetryAt <= now)
      .count()
    if (activeUploads === 0 && ready === 0 && !pumping) {
      return
    }
    await sleep(10)
  }
}

/** 测试隔离：清表、复位状态与计时器（须在 whenIdle 之后调用）。 */
async function resetForTests(): Promise<void> {
  if (retryTimer != null) {
    clearTimeout(retryTimer)
    retryTimer = null
  }
  initialized = false
  await db.uploadQueue.clear()
  state.waitingCount = 0
  state.uploadingCount = 0
  state.unboundCount = 0
  state.failures.splice(0, state.failures.length)
  activeByItem.clear()
  claimedItems.clear()
  pumping = false
  activeUploads = 0
}

export function useUploadQueue() {
  return {
    state,
    activeByItem,
    init,
    addFiles,
    bindItem,
    removeUnbound,
    pump: () => Promise.resolve(pump()),
    whenIdle,
    resetForTests,
  }
}
