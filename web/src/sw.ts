/// <reference lib="webworker" />
import { precacheAndRoute, createHandlerBoundToURL } from 'workbox-precaching'
import { ExpirationPlugin } from 'workbox-expiration'
import { NavigationRoute, registerRoute } from 'workbox-routing'
import { CacheFirst } from 'workbox-strategies'
import type { PrecacheEntry } from 'workbox-precaching'

/**
 * Service Worker（M6-①，docs/01 7.5/移动端节）：
 *
 * - SPA 预缓存（vite-plugin-pwa injectManifest 注入 __WB_MANIFEST）+
 *   导航回退 index.html（/api 永不 SW 兜底——SSE 与业务请求直连）。
 * - 图片 /img /thumb CacheFirst（缩略图 ~25KB、只读不变——30 天/2000 条/
 *   purgeOnQuotaError 配额压力自清理）。
 * - Background Sync（安卓主路径，R2）：网络恢复时 SW 直接回放 IndexedDB
 *   待传照片——页面不必存活。仅做「成功即出清」的最小回放：失败留给
 *   页面泵做退避/永久失败分类（单一职责），幂等键 clientUuid 保证
 *   SW 与页面泵并发双传也安全（服务端 200 读回）。
 */

declare let self: ServiceWorkerGlobalScope

precacheAndRoute((self as ServiceWorkerGlobalScope & { __WB_MANIFEST?: PrecacheEntry[] }).__WB_MANIFEST ?? [])

// SPA 导航：预缓存的 index.html 兜底（API/图片路径除外）
registerRoute(
  new NavigationRoute(createHandlerBoundToURL('index.html'), {
    denylist: [/^\/api\//, /^\/img\//, /^\/thumb\//, /^\/actuator\//],
  }),
)

// 图片缩略图/原图：CacheFirst + 过期治理
registerRoute(
  ({ url }) => url.pathname.startsWith('/img/') || url.pathname.startsWith('/thumb/'),
  new CacheFirst({
    cacheName: 'kcgl-images',
    plugins: [
      new ExpirationPlugin({
        maxEntries: 2000,
        maxAgeSeconds: 30 * 24 * 60 * 60,
        purgeOnQuotaError: true,
      }),
    ],
  }),
)

// ------------------------------------------------------- Background Sync 回放

const SYNC_TAG = 'kcgl-upload-queue'
const DB_NAME = 'kcgl'
const DB_VERSION = 1
const UPLOAD_STORE = 'uploadQueue'

/** Background Sync 的 sync 事件（TS 标准库未收录——最小结构声明）。 */
interface SyncEvent extends ExtendableEvent {
  readonly tag: string
}

self.addEventListener('sync', ((event: SyncEvent) => {
  if (event.tag === SYNC_TAG) {
    event.waitUntil(replayUploadQueue())
  }
}) as EventListener)

/** 原始 IndexedDB 读取待传条目（SW 不引入 Dexie——保持产物零应用依赖）。 */
function readPendingEntries(): Promise<{ clientUuid: string; itemId: number | null; data: ArrayBuffer; mimeType: string }[]> {
  return new Promise((resolve) => {
    const open = indexedDB.open(DB_NAME, DB_VERSION)
    open.onupgradeneeded = () => {
      // 数据库尚未创建（从未传过照片）：无需回放。版本不符也走此分支终止。
      open.transaction?.abort()
    }
    open.onerror = () => resolve([])
    open.onblocked = () => resolve([])
    open.onsuccess = () => {
      const db = open.result
      try {
        if (!db.objectStoreNames.contains(UPLOAD_STORE)) {
          resolve([])
          return
        }
        const request = db.transaction(UPLOAD_STORE, 'readonly').objectStore(UPLOAD_STORE).getAll()
        request.onsuccess = () => {
          resolve(
            (request.result as { clientUuid: string; itemId: number | null; data: ArrayBuffer; mimeType: string; status: string }[])
              .filter((entry) => entry.status === 'pending' && entry.itemId != null),
          )
        }
        request.onerror = () => resolve([])
      } finally {
        db.close()
      }
    }
  })
}

function deleteEntry(clientUuid: string): Promise<void> {
  return new Promise((resolve) => {
    const open = indexedDB.open(DB_NAME, DB_VERSION)
    open.onerror = () => resolve()
    open.onblocked = () => resolve()
    open.onsuccess = () => {
      const db = open.result
      try {
        if (!db.objectStoreNames.contains(UPLOAD_STORE)) {
          resolve()
          return
        }
        const request = db.transaction(UPLOAD_STORE, 'readwrite').objectStore(UPLOAD_STORE).delete(clientUuid)
        request.onsuccess = () => resolve()
        request.onerror = () => resolve()
      } finally {
        db.close()
      }
    }
  })
}

/** 单条回放：与 useUploadQueue.uploadOne 同构的上传载荷（clientUuid 幂等键）。 */
async function replayOne(entry: { clientUuid: string; itemId: number | null; data: ArrayBuffer; mimeType: string }): Promise<boolean> {
  const form = new FormData()
  form.append('file', new Blob([entry.data], { type: entry.mimeType }), `${entry.clientUuid}.jpg`)
  form.append('clientUuid', entry.clientUuid)
  form.append('itemId', String(entry.itemId))
  form.append('imageType', '1')
  try {
    const response = await fetch('/api/images', { method: 'POST', body: form })
    return response.ok
  } catch {
    return false // 网络仍不可用：留给下次（页面泵接管退避）
  }
}

async function replayUploadQueue(): Promise<void> {
  const entries = await readPendingEntries()
  let replayed = 0
  for (const entry of entries) {
    if (await replayOne(entry)) {
      await deleteEntry(entry.clientUuid)
      replayed++
    }
  }
  if (replayed > 0) {
    // 通知存活页面刷新计数并继续泵（无存活页也无妨——下次打开 init 会刷新）
    const clients = await self.clients.matchAll({ type: 'window' })
    for (const client of clients) {
      client.postMessage({ type: 'kcgl-upload-replayed', replayed })
    }
  }
}
