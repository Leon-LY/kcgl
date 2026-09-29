import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  me: vi.fn(),
  fetchVenues: vi.fn(),
  fetchPriceBands: vi.fn(),
  fetchSettings: vi.fn(),
}))

vi.mock('@/utils/api', () => ({
  api: { me: apiMocks.me },
  fetchVenues: apiMocks.fetchVenues,
  fetchPriceBands: apiMocks.fetchPriceBands,
  fetchSettings: apiMocks.fetchSettings,
}))

import { useAuthStore } from './auth'
import { useSyncStore } from './sync'
import { useSse } from '@/composables/useSse'

/** jsdom 无 EventSource：记录实例与回调赋值的最小替身（close 只标记状态）。 */
class FakeEventSource {
  static instances: FakeEventSource[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onerror: (() => void) | null = null
  closed = false
  constructor(public readonly url: string) {
    FakeEventSource.instances.push(this)
  }
  close(): void {
    this.closed = true
  }
}

function meFixture() {
  return {
    id: 101,
    username: 'taro',
    displayName: '田中太郎',
    role: 2,
    locale: 'ja-JP',
    mustChangePwd: false,
  }
}

function event(type: string, operatorId: number | null = null): string {
  return JSON.stringify({ seq: 1, type, entity: null, operatorId, at: '2026-09-28T10:00:00' })
}

/** probeSession 等纯微任务链的确定性排空（无定时器参与）。 */
async function flushMicrotasks(): Promise<void> {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

// document/visibilitychange 是文件级共享资源：登记每个用例的 store， afterEach 统一
// dispose（移除监听/停 watch），否则前序用例的监听器会污染后续用例的实例计数
const activeStores: ReturnType<typeof useSyncStore>[] = []

function setupSync(): { auth: ReturnType<typeof useAuthStore>; sync: ReturnType<typeof useSyncStore> } {
  const auth = useAuthStore()
  const sync = useSyncStore()
  sync.init()
  activeStores.push(sync)
  return { auth, sync }
}

/** 登录并返回当前连接：watch 回调按 Vue 调度异步执行，须等一拍再取实例。 */
async function login(auth: ReturnType<typeof useAuthStore>): Promise<FakeEventSource> {
  auth.me = meFixture()
  await nextTick()
  const instance = FakeEventSource.instances.at(-1)
  if (!instance) throw new Error('EventSource 未建立')
  return instance
}

beforeEach(() => {
  setActivePinia(createPinia())
  FakeEventSource.instances = []
  vi.stubGlobal('EventSource', FakeEventSource)
  apiMocks.me.mockReset()
  apiMocks.fetchVenues.mockReset().mockResolvedValue([])
  apiMocks.fetchPriceBands.mockReset().mockResolvedValue([])
  apiMocks.fetchSettings.mockReset().mockResolvedValue({
    warnDays: 30,
    alarmDays: 90,
    labelPreset: 'small',
    labelWidthMm: 50,
    labelHeightMm: 30,
  })
})

afterEach(() => {
  for (const sync of activeStores.splice(0)) {
    sync.dispose()
  }
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('session-driven lifecycle', () => {
  it('stays disconnected for guests, connects on login, closes on logout, never stacks connections', async () => {
    const { auth, sync } = setupSync()
    expect(FakeEventSource.instances).toHaveLength(0) // 游客态不建连

    const es = await login(auth)
    expect(es.url).toBe('/api/sync/events')

    auth.me = { ...meFixture(), username: 'hanako' } // 会话切换（非登出）不叠连接
    expect(FakeEventSource.instances).toHaveLength(1)

    auth.me = null // 登出/被清会话 → 立即断开
    await nextTick()
    expect(es.closed).toBe(true)
    expect(sync.connected).toBe(false)
  })

  it('init is idempotent and dispose stops session-driven reconnection', async () => {
    const { auth } = setupSync()
    const sync = useSyncStore()
    sync.init() // 二次 init 幂等
    await login(auth)
    expect(FakeEventSource.instances).toHaveLength(1)

    sync.dispose()
    auth.me = null
    await login(auth)
    expect(FakeEventSource.instances).toHaveLength(1) // dispose 后不再建连
  })

  it('useSse mounts the singleton (App.vue mount point)', () => {
    const auth = useAuthStore()
    auth.me = meFixture()
    useSse()
    const sync = useSyncStore()
    activeStores.push(sync)
    expect(FakeEventSource.instances).toHaveLength(1) // 挂载即按会话态连接
  })
})

describe('self-echo suppression (D-070 regression: stocktake.spec full-flow line 220)', () => {
  // 根因：自己处理完最后一条差异后，STOCKTAKE/INVENTORY 回声触发 pending-only
  // 重取，刚处理完的行被清出列表（就地行更新被自己的回声覆盖）。修复=回声不派发失效。
  it('drops events carrying my own operatorId: no invalidation, no refetch race on own views', async () => {
    vi.useFakeTimers()
    const { auth } = setupSync()
    const sync = useSyncStore()
    const seen = vi.fn()
    sync.onInvalidate(seen)
    const es = await login(auth) // me.id = 101

    es.onmessage?.({ data: event('STOCKTAKE', 101) })
    es.onmessage?.({ data: event('INVENTORY', 101) })
    es.onmessage?.({ data: event('DICT', 101) })
    await vi.advanceTimersByTimeAsync(500)

    expect(seen).not.toHaveBeenCalled()
    expect(apiMocks.fetchVenues).not.toHaveBeenCalled()
  })

  it('still dispatches other-user and system events (cross-user realtime contract intact)', async () => {
    vi.useFakeTimers()
    const { auth } = setupSync()
    const sync = useSyncStore()
    const seen = vi.fn()
    sync.onInvalidate(seen)
    const es = await login(auth)

    es.onmessage?.({ data: event('STOCKTAKE', 202) }) // 他端操作
    es.onmessage?.({ data: event('DICT', null) }) // 系统事件（字典域现值不带操作人）
    await vi.advanceTimersByTimeAsync(500)

    expect(seen).toHaveBeenCalledTimes(1)
    expect(Array.from(seen.mock.calls[0][0] as Set<string>).sort())
      .toEqual(['DICT', 'STOCKTAKE'])
    expect(apiMocks.fetchVenues).toHaveBeenCalledTimes(1)
  })
})

describe('event handling', () => {
  it('marks connected on open and ignores HELLO (connection ack, no invalidation)', async () => {
    vi.useFakeTimers()
    const { auth, sync } = setupSync()
    const es = await login(auth)

    es.onopen?.()
    expect(sync.connected).toBe(true)

    es.onmessage?.({ data: event('HELLO') })
    await vi.advanceTimersByTimeAsync(600)
    expect(apiMocks.fetchVenues).not.toHaveBeenCalled()
  })

  it('drops malformed payloads and unknown types without poisoning the stream', async () => {
    vi.useFakeTimers()
    const { auth, sync } = setupSync()
    const es = await login(auth)

    es.onmessage?.({ data: 'not-json' })
    es.onmessage?.({ data: JSON.stringify({ seq: 2, type: 'FUTURE_TYPE', entity: null, operatorId: null, at: '' }) })
    expect(sync.lastEventAt).not.toBeNull() // 合法信封仍记时（未知类型只是不失效）

    await vi.advanceTimersByTimeAsync(600)
    expect(apiMocks.fetchVenues).not.toHaveBeenCalled()
  })

  it('debounces a burst into one coalesced flush: DICT reloads dicts, listeners get the type set', async () => {
    vi.useFakeTimers()
    const { auth } = setupSync()
    const sync = useSyncStore()
    const seen = vi.fn()
    sync.onInvalidate(seen)
    const es = await login(auth)

    es.onmessage?.({ data: event('DICT') })
    es.onmessage?.({ data: event('DICT') })
    es.onmessage?.({ data: event('ITEM') })
    es.onmessage?.({ data: event('INVENTORY') })
    expect(apiMocks.fetchVenues).not.toHaveBeenCalled() // 防抖窗口内不重取

    await vi.advanceTimersByTimeAsync(500)
    expect(apiMocks.fetchVenues).toHaveBeenCalledTimes(1)
    expect(apiMocks.fetchPriceBands).toHaveBeenCalledTimes(1)
    expect(seen).toHaveBeenCalledTimes(1)
    expect(Array.from(seen.mock.calls[0][0] as Set<string>).sort())
      .toEqual(['DICT', 'INVENTORY', 'ITEM'])
  })

  it('SETTING invalidation reloads the settings cache (other-end threshold/label changes)', async () => {
    vi.useFakeTimers()
    const { auth } = setupSync()
    const sync = useSyncStore()
    const seen = vi.fn()
    sync.onInvalidate(seen)
    const es = await login(auth)

    es.onmessage?.({ data: event('SETTING', 202) }) // 他端管理员改设置
    await vi.advanceTimersByTimeAsync(500)

    expect(apiMocks.fetchSettings).toHaveBeenCalledTimes(1)
    // SETTING 同时照常分发给视图监听者（大盘滞销计数等聚合面自行决定重取）
    expect(seen).toHaveBeenCalledTimes(1)
    expect(seen.mock.calls[0][0]).toEqual(new Set(['SETTING']))
  })

  it('isolates a throwing listener from other listeners', async () => {
    vi.useFakeTimers()
    const { auth } = setupSync()
    const sync = useSyncStore()
    const good = vi.fn()
    sync.onInvalidate(() => {
      throw new Error('listener bug')
    })
    sync.onInvalidate(good)
    const es = await login(auth)

    es.onmessage?.({ data: event('ITEM') })
    await vi.advanceTimersByTimeAsync(500)
    expect(good).toHaveBeenCalledTimes(1)
  })

  it('unsubscribes listeners via the returned disposer', async () => {
    vi.useFakeTimers()
    const { auth } = setupSync()
    const sync = useSyncStore()
    const seen = vi.fn()
    const off = sync.onInvalidate(seen)
    off()
    const es = await login(auth)

    es.onmessage?.({ data: event('ITEM') })
    await vi.advanceTimersByTimeAsync(500)
    expect(seen).not.toHaveBeenCalled()
  })
})

describe('error recovery', () => {
  it('closes the stream after the probe finds the session gone (401 → stop reconnect storm)', async () => {
    const { auth } = setupSync()
    const es = await login(auth)

    apiMocks.me.mockRejectedValue(new Error('401'))
    es.onerror?.()
    expect(es.closed).toBe(false) // 错误当下不贸然关——先探测

    auth.me = null // 模拟 api 层 401 全局处理器清会话（router/index.ts 注册的接线）
    await flushMicrotasks()
    expect(es.closed).toBe(true)
  })

  it('keeps the stream when the probe succeeds (network blip → native reconnect heals)', async () => {
    const { auth, sync } = setupSync()
    const es = await login(auth)

    apiMocks.me.mockResolvedValue(meFixture())
    es.onerror?.()
    await flushMicrotasks()
    expect(es.closed).toBe(false)
    expect(sync.connected).toBe(false) // 断线态如实呈现，等待原生重连
  })

  it('fully refreshes when the reconnect gap exceeded 5s', async () => {
    vi.useFakeTimers()
    const { auth } = setupSync()
    const es = await login(auth)
    apiMocks.me.mockResolvedValue(meFixture())

    es.onopen?.() // 首连：无断线记录，不刷新
    await vi.advanceTimersByTimeAsync(600)
    expect(apiMocks.fetchVenues).not.toHaveBeenCalled()

    es.onerror?.() // t=0 断线（原生重连期间探测通过）
    await flushMicrotasks()
    vi.advanceTimersByTime(6000)
    es.onopen?.() // 重连成功：断线 6s > 5s
    await vi.advanceTimersByTimeAsync(500)
    expect(apiMocks.fetchVenues).toHaveBeenCalledTimes(1) // 全量失效兜底
  })

  it('does not full-refresh on a sub-5s blip', async () => {
    vi.useFakeTimers()
    const { auth } = setupSync()
    const es = await login(auth)
    apiMocks.me.mockResolvedValue(meFixture())

    es.onopen?.()
    es.onerror?.()
    await flushMicrotasks()
    vi.advanceTimersByTime(2000)
    es.onopen?.() // 2s 内重连——按事件失效即可
    await vi.advanceTimersByTimeAsync(500)
    expect(apiMocks.fetchVenues).not.toHaveBeenCalled()
  })
})

describe('foreground rebuild', () => {
  it('rebuilds the stream and fully invalidates on visibilitychange → visible', async () => {
    vi.useFakeTimers()
    const { auth } = setupSync()
    const first = await login(auth)

    document.dispatchEvent(new Event('visibilitychange')) // jsdom 默认 visible
    expect(first.closed).toBe(true) // 旧连接主动关闭
    expect(FakeEventSource.instances).toHaveLength(2) // 新连接已建立

    await vi.advanceTimersByTimeAsync(500)
    expect(apiMocks.fetchVenues).toHaveBeenCalledTimes(1) // 挂起窗口不可知——保守全量失效
  })

  it('ignores visibility changes while logged out', async () => {
    vi.useFakeTimers()
    setupSync() // 游客态
    document.dispatchEvent(new Event('visibilitychange'))
    expect(FakeEventSource.instances).toHaveLength(0)
  })
})
