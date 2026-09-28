import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  me: vi.fn(),
  fetchVenues: vi.fn(),
  fetchPriceBands: vi.fn(),
}))

vi.mock('@/utils/api', () => ({
  api: { me: apiMocks.me },
  fetchVenues: apiMocks.fetchVenues,
  fetchPriceBands: apiMocks.fetchPriceBands,
}))

import { useAuthStore } from '@/stores/auth'
import { useSyncStore } from '@/stores/sync'
import { useSyncInvalidation } from './useSyncInvalidation'

/**
 * 列表视图的 SSE 失效接线：命中关注的类型→调 reload；未命中不打扰；
 * 组件卸载自动注销（路由离开后旧页面不再重取）。防抖(500ms)由 store
 * 层承担，本测试经 FakeEventSource 注入事件后推进定时器验证全链。
 */

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
    username: 'taro',
    displayName: '田中太郎',
    role: 2,
    locale: 'ja-JP',
    mustChangePwd: false,
  }
}

function event(type: string): string {
  return JSON.stringify({ seq: 1, type, entity: null, operatorId: null, at: '2026-09-28T10:00:00' })
}

/** 挂载一个使用接线的宿主组件（模拟列表视图：reload=重置回第 1 页）。 */
function mountHost(types: readonly string[], reload: () => void) {
  return mount(
    defineComponent({
      setup() {
        useSyncInvalidation(types, reload)
        return () => h('div')
      },
    }),
  )
}

const activeStores: ReturnType<typeof useSyncStore>[] = []

beforeEach(() => {
  setActivePinia(createPinia())
  FakeEventSource.instances = []
  vi.stubGlobal('EventSource', FakeEventSource)
  apiMocks.me.mockReset()
  apiMocks.fetchVenues.mockReset().mockResolvedValue([])
  apiMocks.fetchPriceBands.mockReset().mockResolvedValue([])
})

afterEach(() => {
  for (const sync of activeStores.splice(0)) {
    sync.dispose()
  }
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

enableAutoUnmount(afterEach)

describe('useSyncInvalidation', () => {
  it('reloads once for a matching type (debounced burst coalesced by the store)', async () => {
    vi.useFakeTimers()
    const auth = useAuthStore()
    auth.me = meFixture()
    const sync = useSyncStore()
    sync.init()
    activeStores.push(sync)
    await nextTick()

    const reload = vi.fn()
    mountHost(['ITEM', 'INVENTORY'], reload)
    const es = FakeEventSource.instances.at(-1)!
    es.onopen?.()

    es.onmessage?.({ data: event('INVENTORY') })
    es.onmessage?.({ data: event('ITEM') })
    expect(reload).not.toHaveBeenCalled() // 防抖窗口内不重取

    await vi.advanceTimersByTimeAsync(500)
    expect(reload).toHaveBeenCalledTimes(1) // 突发合并为一次
  })

  it('ignores types outside the accepted set', async () => {
    vi.useFakeTimers()
    const auth = useAuthStore()
    auth.me = meFixture()
    const sync = useSyncStore()
    sync.init()
    activeStores.push(sync)
    await nextTick()

    const reload = vi.fn()
    mountHost(['STOCKTAKE'], reload)
    const es = FakeEventSource.instances.at(-1)!

    es.onmessage?.({ data: event('ITEM') })
    es.onmessage?.({ data: event('INVENTORY') })
    await vi.advanceTimersByTimeAsync(600)
    expect(reload).not.toHaveBeenCalled()
  })

  it('unsubscribes on unmount (navigated-away views stop reloading)', async () => {
    vi.useFakeTimers()
    const auth = useAuthStore()
    auth.me = meFixture()
    const sync = useSyncStore()
    sync.init()
    activeStores.push(sync)
    await nextTick()

    const reload = vi.fn()
    const host = mountHost(['ITEM'], reload)
    const es = FakeEventSource.instances.at(-1)!

    es.onmessage?.({ data: event('ITEM') })
    await vi.advanceTimersByTimeAsync(500)
    expect(reload).toHaveBeenCalledTimes(1)

    host.unmount()
    es.onmessage?.({ data: event('ITEM') })
    await vi.advanceTimersByTimeAsync(500)
    expect(reload).toHaveBeenCalledTimes(1) // 卸载后不再触发
  })
})
