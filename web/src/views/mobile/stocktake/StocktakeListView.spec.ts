import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  createStocktake: vi.fn(),
  fetchStocktakes: vi.fn(),
}))

// ApiError 保持真实实现（409009 分支依赖 instanceof/code）；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    createStocktake: apiMocks.createStocktake,
    fetchStocktakes: apiMocks.fetchStocktakes,
  }
})

const pushMock = vi.hoisted(() => vi.fn())

// StocktakeListView 仅用 useRouter；提供最小 push 桩以便断言导航目标
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRouter: () => ({ push: pushMock }),
  }
})

import StocktakeListView from './StocktakeListView.vue'
import { i18n } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/utils/api'
import type { MeResponse, StocktakeList, StocktakeSummary } from '@/utils/api'

/**
 * 盘点首页（M3-⑥）：发起选仓/409009 引导直达既有单/历史列表（进行中与
 * close 后两种计数文案）/viewer 只读。
 */

const meEditor: MeResponse = {
  id: 2,
  username: 'eichi',
  displayName: '編集者',
  role: 2,
  locale: 'ja-JP',
  mustChangePwd: false,
}

const meViewer: MeResponse = {
  id: 3,
  username: 'miru',
  displayName: '閲覧者',
  role: 3,
  locale: 'ja-JP',
  mustChangePwd: false,
}

function summary(overrides: Partial<StocktakeSummary> = {}): StocktakeSummary {
  return {
    id: 5,
    stocktakeNo: 'PD20260928-01',
    warehouse: 1,
    status: 0,
    expectedCount: null,
    scannedCount: 3,
    diffCount: null,
    pendingDiffCount: null,
    createdAt: '2026-09-28 09:00:00',
    closedAt: null,
    createdByName: '編集者',
    closedByName: null,
    mine: false,
    ...overrides,
  }
}

function list(rows: StocktakeSummary[], total?: number): StocktakeList {
  return { total: total ?? rows.length, page: 1, size: rows.length, rows }
}

async function mountView(role: 2 | 3 = 2): Promise<VueWrapper> {
  const auth = useAuthStore()
  auth.me = role === 2 ? meEditor : meViewer
  const wrapper = mount(StocktakeListView, {
    global: { plugins: [i18n] },
  })
  await flushPromises()
  return wrapper
}

/** 等待断言条件成立（van-list 挂载/滚动触发时机跨任务，flushPromises 盖不住）。 */
async function waitFor(probe: () => boolean, timeoutMs = 1000): Promise<void> {
  const start = Date.now()
  while (!probe()) {
    if (Date.now() - start > timeoutMs) {
      throw new Error('waitFor 超时')
    }
    await new Promise((resolve) => setTimeout(resolve, 10))
  }
  await flushPromises()
}

enableAutoUnmount(afterEach)

/**
 * jsdom 无布局引擎：Vant List 的 check() 以「滚动容器高度>0 且列表根未被
 * 判隐藏」为触发前提——按组件语义打最小桩（同 ArrivalView.spec，D-038）。
 */
const VIEWPORT_RECT: DOMRect = {
  top: 0,
  bottom: 600,
  left: 0,
  right: 375,
  width: 375,
  height: 600,
  x: 0,
  y: 0,
  toJSON: () => ({}),
} as DOMRect

beforeEach(() => {
  vi.resetAllMocks()
  pushMock.mockReset()
  setActivePinia(createPinia())
  i18n.global.locale.value = 'ja-JP'
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue(VIEWPORT_RECT)
  vi.spyOn(HTMLElement.prototype, 'offsetParent', 'get').mockReturnValue(document.body)
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('stocktake list (M3-6)', () => {
  it('renders history rows with status tags, counts, and creator info', async () => {
    apiMocks.fetchStocktakes.mockResolvedValue(
      list([
        summary(),
        summary({ id: 6, stocktakeNo: 'PD20260927-02', status: 1, expectedCount: 10, scannedCount: 8, diffCount: 2, pendingDiffCount: 2 }),
      ]),
    )
    const wrapper = await mountView()

    await waitFor(() => wrapper.findAll('.stocktake-row').length === 2)
    const rows = wrapper.findAll('.stocktake-row')
    // 进行中：扫描数；close 后：期望/扫描 + 待确认差异
    expect(rows[0]!.text()).toContain('PD20260928-01')
    expect(rows[0]!.text()).toContain('実行中')
    expect(rows[0]!.text()).toContain('スキャン済み 3 件')
    expect(rows[1]!.text()).toContain('確認待ち')
    expect(rows[1]!.text()).toContain('予定 10 件・スキャン 8 件')
    expect(rows[1]!.text()).toContain('未確認 2 件')
    expect(rows[0]!.text()).toContain('名古屋倉庫')
    expect(rows[0]!.text()).toContain('開始：編集者（2026-09-28）')
  })

  it('navigates to the session page after starting a stocktake', async () => {
    apiMocks.fetchStocktakes.mockResolvedValue(list([]))
    apiMocks.createStocktake.mockResolvedValue(summary({ id: 9, warehouse: 2 }))
    const wrapper = await mountView()
    await waitFor(() => apiMocks.fetchStocktakes.mock.calls.length > 0)

    // label 包 radio：jsdom 点击 label 不转发，直接对 radio 触发 change（v-model）
    await wrapper.findAll<HTMLInputElement>('input[name="stocktake-start-wh"]')[1]!.setValue(true)
    await wrapper.find('.stocktake-start .kcgl-btn-primary').trigger('click')
    await flushPromises()

    expect(apiMocks.createStocktake).toHaveBeenCalledWith(2)
    expect(pushMock).toHaveBeenCalledWith({ name: 'stocktake-session', params: { id: 9 } })
  })

  it('offers a direct link to the active stocktake on 409009', async () => {
    // 409009 后端语义：同仓已有进行中单——onStart 里按 status=0 再查一次给直达入口；
    // 列表初次加载（status undefined）与该查询按参数分流
    apiMocks.fetchStocktakes.mockImplementation(
      async (_page: number, _size: number, status?: number) =>
        status === 0 ? list([summary({ id: 7, warehouse: 1, status: 0 })]) : list([]),
    )
    apiMocks.createStocktake.mockRejectedValue(new ApiError(409009, 'STOCKTAKE_ACTIVE_EXISTS'))

    const wrapper = await mountView()
    await waitFor(() => apiMocks.fetchStocktakes.mock.calls.length > 0)
    await wrapper.find('.stocktake-start .kcgl-btn-primary').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('この倉庫では実行中の棚卸があります')
    expect(wrapper.text()).toContain('実行中の棚卸を開く')

    await wrapper.find('.stocktake-start-open').trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ name: 'stocktake-session', params: { id: 7 } })
  })

  it('opens the session page when a history row is tapped', async () => {
    apiMocks.fetchStocktakes.mockResolvedValue(list([summary({ id: 6 })]))
    const wrapper = await mountView()

    await waitFor(() => wrapper.findAll('.stocktake-row').length === 1)
    await wrapper.find('.stocktake-row').trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ name: 'stocktake-session', params: { id: 6 } })
  })

  it('shows a viewer note instead of the start card for viewers', async () => {
    apiMocks.fetchStocktakes.mockResolvedValue(list([]))
    const wrapper = await mountView(3)

    expect(wrapper.find('.stocktake-start').exists()).toBe(false)
    expect(wrapper.text()).toContain('棚卸の開始と操作には編集者以上の権限が必要です')
    expect(apiMocks.createStocktake).not.toHaveBeenCalled()
  })

  it('shows the empty state and a retry hint on load failure', async () => {
    apiMocks.fetchStocktakes.mockResolvedValue(list([]))
    const wrapper = await mountView()
    await waitFor(() => wrapper.find('.stocktake-empty').exists())
    expect(wrapper.text()).toContain('棚卸の記録はありません')

    apiMocks.fetchStocktakes.mockReset()
    apiMocks.fetchStocktakes.mockRejectedValue(new ApiError(0, 'NETWORK_ERROR'))
    const failed = await mountView()
    await waitFor(() => failed.find('.van-list__error-text').exists())
    expect(failed.find('.van-list__error-text').text()).toContain('読み込みに失敗しました')
  })
})
