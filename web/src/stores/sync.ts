import { defineStore } from 'pinia'
import { ref, watch, type WatchStopHandle } from 'vue'
import { api } from '@/utils/api'
import { useAuthStore } from '@/stores/auth'
import { useDictsStore } from '@/stores/dicts'
import { useSettingsStore } from '@/stores/settings'

/**
 * 实时同步单例（docs/01 7.6）：EventSource 长连接 + 粗粒度失效分发。
 *
 * 生命周期：登录即连/登出即断（watch 会话态；路由切换不重建——单例是防
 * emitter 泄漏的唯一防线）；出错先探测 /auth/me——401 由 api 层全局处理器
 * 清会话跳登录，此处仅 close 防原生重连对 401 无限风暴；网络抖动交原生重连自愈。
 * iOS 挂起掐线后原生重连不可靠：回前台主动重建并保守全量失效；
 * 断线超 5s 恢复亦全量失效（错过的窗口无法按事件补）。
 *
 * 处理策略：粗粒度失效 + 500ms 防抖重取（不传增量补丁——重取成本低且无补丁 bug）。
 * 回声抑制（D-070）：自己操作的广播（operatorId===me.id）不触发失效——自己的
 * 屏幕由操作响应驱动（就地行更新/提交回显），回声重取反而会与自己的更新竞态
 * （盘点差异页 pending 过滤重取曾抹掉刚处理完的行）；异步批次页（Excel/Yahoo）
 * 有轮询主路径，不依赖自己的回声。代价：同账号他设备的自动刷新随之让渡（跨
 * 用户实时一致不受影响——验收承诺的口径）。
 * 字典与系统设置是两个常驻业务缓存，DICT/SETTING 失效分别驱动
 * dicts.reload / settings.reload；其余域经 onInvalidate 监听分发
 * （M3-④/⑤ 起列表视图接入）。
 */

/** 后端事件信封（server SyncEvent）：{seq,type,entity,operatorId,at}。 */
export interface SyncEvent {
  seq: number
  type: string
  entity: string | null
  operatorId: number | null
  at: string
}

/** 业务资源域：全量失效时的失效集合（HELLO=连接确认，除外）。 */
const BUSINESS_TYPES = [
  'ITEM',
  'INVENTORY',
  'IMAGE',
  'STOCKTAKE',
  'YAHOO_IMPORT',
  'EXCEL_IMPORT',
  'SETTING',
  'DICT',
] as const

const SYNC_URL = '/api/sync/events'
const INVALIDATE_DEBOUNCE_MS = 500
const RECONNECT_FULL_REFRESH_MS = 5_000

export const useSyncStore = defineStore('sync', () => {
  const auth = useAuthStore()
  const dicts = useDictsStore()
  const settings = useSettingsStore()

  const connected = ref(false)
  const lastEventAt = ref<number | null>(null)

  let es: EventSource | null = null
  let disconnectedAt: number | null = null
  let stopWatchMe: WatchStopHandle | null = null
  let onVisibility: (() => void) | null = null
  let started = false
  const pending = new Set<string>()
  let flushTimer: ReturnType<typeof setTimeout> | null = null
  const listeners = new Set<(types: ReadonlySet<string>) => void>()

  function connect(): void {
    if (es) {
      return // 已连接/连接中（构造即开始连接）；会话切换不叠连接
    }
    connected.value = false
    const source = new EventSource(SYNC_URL)
    es = source
    // 陈旧接线守卫：重建/关闭后旧连接的迟到回调不再触达当前状态
    source.onopen = () => {
      if (es !== source) return
      connected.value = true
      if (disconnectedAt != null && Date.now() - disconnectedAt > RECONNECT_FULL_REFRESH_MS) {
        scheduleInvalidateAll() // 断线窗口过长：放弃增量语义，全量失效兜底
      }
      disconnectedAt = null
    }
    source.onmessage = (message: MessageEvent) => {
      if (es !== source) return
      handleEvent(String(message.data))
    }
    source.onerror = () => {
      if (es !== source) return
      connected.value = false
      disconnectedAt ??= Date.now()
      void probeSession()
    }
  }

  function disconnect(): void {
    if (!es) return
    const source = es
    es = null
    source.close()
    connected.value = false
  }

  /** 回前台重建：先断旧连接（挂起期间链路已不可信）再新建。 */
  function rebuild(): void {
    disconnect()
    connect()
  }

  /**
   * 错误探测：会话已失效（401 已被 api 层全局处理器清态跳登录）→ close 防风暴；
   * 探测成功或纯网络错误（会话仍在）→ 原生重连自愈，不干预。
   */
  async function probeSession(): Promise<void> {
    try {
      await api.me()
    } catch {
      if (!auth.me) {
        disconnect()
      }
    }
  }

  function handleEvent(raw: string): void {
    let event: SyncEvent
    try {
      event = JSON.parse(raw) as SyncEvent
    } catch {
      return // 单条信封解析失败仅丢弃，不毒化整条连接
    }
    lastEventAt.value = Date.now()
    if (event.type === 'HELLO') return
    // 回声抑制（D-070）：自己操作的广播不失效自己的视图（operatorId=null 系统
    // 事件与会话未就绪时不抑制——宁可多重取不可漏事件）
    if (event.operatorId != null && auth.me != null && event.operatorId === auth.me.id) {
      return
    }
    if ((BUSINESS_TYPES as readonly string[]).includes(event.type)) {
      scheduleInvalidate(event.type)
    }
  }

  function scheduleInvalidate(type: string): void {
    pending.add(type)
    armFlushTimer()
  }

  function scheduleInvalidateAll(): void {
    for (const type of BUSINESS_TYPES) {
      pending.add(type)
    }
    armFlushTimer()
  }

  function armFlushTimer(): void {
    if (flushTimer != null) return
    // 窗口自首条事件起算（不随后续事件重置）：持续事件流下刷新延迟有上界
    flushTimer = setTimeout(flushPending, INVALIDATE_DEBOUNCE_MS)
  }

  function flushPending(): void {
    flushTimer = null
    const types = new Set(pending)
    pending.clear()
    if (types.has('DICT')) {
      // 字典重取失败不阻断其余监听者；下次失效或视图 ensureLoaded 自然重试
      void dicts.reload().catch(() => undefined)
    }
    if (types.has('SETTING')) {
      // 设置缓存同款失效（M5-③：他端改阈值/标签规格 → 打印页默认规格重取；
      // reload 内部吞错保旧值，广播链路不外抛）
      void settings.reload()
    }
    for (const listener of listeners) {
      try {
        listener(types)
      } catch {
        // 单个监听者异常不外溢到其他监听者与后续事件
      }
    }
  }

  /** 注册粗粒度失效监听（列表视图据此整体重取）；返回注销函数。 */
  function onInvalidate(listener: (types: ReadonlySet<string>) => void): () => void {
    listeners.add(listener)
    return () => {
      listeners.delete(listener)
    }
  }

  /**
   * 挂载点唯一（useSse → App.vue）：接管会话联动与可见性重建。
   * 幂等；dispose 供测试隔离，生产不调（应用级单例）。
   */
  function init(): void {
    if (started) return
    started = true
    stopWatchMe = watch(
      () => auth.me,
      (me) => (me ? connect() : disconnect()),
      { immediate: true },
    )
    onVisibility = () => {
      if (document.visibilityState !== 'visible' || !auth.me) return
      rebuild()
      scheduleInvalidateAll() // 挂起期间错过的事件不可知——保守全量失效
    }
    document.addEventListener('visibilitychange', onVisibility)
  }

  function dispose(): void {
    started = false
    disconnect()
    stopWatchMe?.()
    stopWatchMe = null
    if (onVisibility) {
      document.removeEventListener('visibilitychange', onVisibility)
      onVisibility = null
    }
    if (flushTimer != null) {
      clearTimeout(flushTimer)
      flushTimer = null
    }
    pending.clear()
    listeners.clear()
  }

  return { connected, lastEventAt, init, dispose, onInvalidate }
})
