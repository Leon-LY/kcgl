import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  fetchPendingArrivals: vi.fn(),
  confirmArrivals: vi.fn(),
}))

// ApiError 保持真实实现（错误文案分支依赖 instanceof/code）；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchPendingArrivals: apiMocks.fetchPendingArrivals,
    confirmArrivals: apiMocks.confirmArrivals,
  }
})

import ArrivalView from './ArrivalView.vue'
import { i18n } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/utils/api'
import type { MeResponse, PendingArrival, PendingArrivalList } from '@/utils/api'

/**
 * 到货核对页（M2-8a）：在途清单渲染/仓库筛选/选择交互/批量确认
 * （幂等键复用契约 docs/01 7.0——失败重试同键重放）/viewer 只读。
 */

type ConfirmCall = [{ itemId: number; clientReqId: string }[], string | undefined]

const meEditor: MeResponse = {
  username: 'eichi',
  displayName: '編集者',
  role: 2,
  locale: 'ja-JP',
  mustChangePwd: false,
}

const meViewer: MeResponse = {
  username: 'miru',
  displayName: '閲覧者',
  role: 3,
  locale: 'ja-JP',
  mustChangePwd: false,
}

function row(id: number, itemCode: string, warehouse: 1 | 2): PendingArrival {
  return { id, itemCode, buyDate: '2026-09-15', thumbUrl: null, warehouse }
}

function page(rows: PendingArrival[], total?: number): PendingArrivalList {
  return { total: total ?? rows.length, page: 1, size: rows.length, rows }
}

function cards(wrapper: VueWrapper) {
  return wrapper.findAll('.arrival-card')
}

async function mountView(role: 2 | 3 = 2): Promise<VueWrapper> {
  const auth = useAuthStore()
  auth.me = role === 2 ? meEditor : meViewer
  const wrapper = mount(ArrivalView, {
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
 * 判隐藏」为触发前提（useRect 全 0、offsetParent 恒 null 都会直接跳过）——
 * 按组件语义打最小桩：600px 高的视口矩形、元素处于常规布局流。
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
  setActivePinia(createPinia())
  i18n.global.locale.value = 'ja-JP'
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue(VIEWPORT_RECT)
  vi.spyOn(HTMLElement.prototype, 'offsetParent', 'get').mockReturnValue(document.body)
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('arrival check (M2-8a)', () => {
  it('renders pending items with count, warehouse tags, and the purchase date', async () => {
    apiMocks.fetchPendingArrivals.mockResolvedValue(
      page([row(101, 'HTK9-A1X', 1), row(102, 'HTK9-A2X', 2)]),
    )
    const wrapper = await mountView()

    await waitFor(() => cards(wrapper).length === 2)
    expect(wrapper.text()).toContain('HTK9-A1X')
    expect(wrapper.text()).toContain('HTK9-A2X')
    expect(wrapper.text()).toContain('入庫待ち 2 件')
    expect(wrapper.text()).toContain('名古屋倉庫')
    expect(wrapper.text()).toContain('福岡倉庫')
    expect(wrapper.text()).toContain('落札日 2026/09/15')
    // 一次到齐 → 不再拉第 2 页
    expect(apiMocks.fetchPendingArrivals).toHaveBeenCalledTimes(1)
    expect(apiMocks.fetchPendingArrivals).toHaveBeenCalledWith(null, 1, 20)
  })

  it('loads the next page until finished and then shows the end-of-list text', async () => {
    apiMocks.fetchPendingArrivals.mockImplementation(
      async (_warehouse: number | null, p: number) =>
        p === 1
          ? { total: 3, page: 1, size: 2, rows: [row(103, 'HTK9-A3X', 1), row(102, 'HTK9-A2X', 1)] }
          : { total: 3, page: 2, size: 2, rows: [row(101, 'HTK9-A1X', 1)] },
    )
    const wrapper = await mountView()

    // jsdom 无真实布局：靠滚动事件驱动 van-list 的 check（视口恒可达底）
    for (let i = 0; i < 10 && cards(wrapper).length < 3; i++) {
      window.dispatchEvent(new Event('scroll'))
      await new Promise((resolve) => setTimeout(resolve, 10))
    }
    await waitFor(() => cards(wrapper).length === 3)

    const [lastWarehouse, lastPage] = apiMocks.fetchPendingArrivals.mock.calls.at(-1)!.slice(0, 2)
    expect(lastWarehouse).toBeNull()
    expect(lastPage).toBe(2)
    expect(wrapper.find('.van-list__finished-text').text()).toContain('これで全部です')
  })

  it('switching the warehouse filter refetches page 1 and clears the selection', async () => {
    apiMocks.fetchPendingArrivals.mockImplementation(async (warehouse: number | null) =>
      warehouse === 2
        ? page([row(102, 'HTK9-A2X', 2)])
        : page([row(101, 'HTK9-A1X', 1), row(102, 'HTK9-A2X', 2)]),
    )
    const wrapper = await mountView()
    await waitFor(() => cards(wrapper).length === 2)

    await cards(wrapper)[0]!.trigger('click')
    expect(wrapper.find('.arrival-actionbar button').attributes('disabled')).toBeUndefined()

    await wrapper.findAll('.arrival-filter-option')[2]!.trigger('click')
    await waitFor(() => cards(wrapper).length === 1)

    expect(wrapper.findAll('.arrival-filter-option')[2]!.classes()).toContain('is-active')
    const [warehouseArg, pageArg] = apiMocks.fetchPendingArrivals.mock.calls.at(-1)!.slice(0, 2)
    expect(warehouseArg).toBe(2)
    expect(pageArg).toBe(1)
    // 选择已清空 → 操作条回到禁用
    expect(wrapper.find('.arrival-actionbar button').attributes('disabled')).toBeDefined()
  })

  it('confirms selected items with the chosen date, then clears and reloads the list', async () => {
    apiMocks.fetchPendingArrivals.mockResolvedValue(
      page([row(101, 'HTK9-A1X', 1), row(102, 'HTK9-A2X', 2)]),
    )
    const wrapper = await mountView()
    await waitFor(() => cards(wrapper).length === 2)

    await cards(wrapper)[0]!.trigger('click')
    await cards(wrapper)[1]!.trigger('click')
    const actionButton = wrapper.find('.arrival-actionbar button')
    expect(actionButton.text()).toContain('2件を入庫確認')

    await actionButton.trigger('click')
    expect(wrapper.find('.arrival-dialog').exists()).toBe(true)
    expect(wrapper.text()).toContain('対象 2 件')
    await wrapper.find('#arrival-in-date').setValue('2026-09-01')

    apiMocks.confirmArrivals.mockResolvedValue({ arrivedCount: 2, items: [] })
    await wrapper.find('.arrival-dialog-ok').trigger('click')
    await flushPromises()

    expect(apiMocks.confirmArrivals).toHaveBeenCalledTimes(1)
    const [lines, inDate] = apiMocks.confirmArrivals.mock.calls[0] as unknown as ConfirmCall
    expect(lines.map((line) => line.itemId)).toEqual([101, 102])
    for (const line of lines) {
      expect(line.clientReqId).toMatch(/^[0-9a-f-]{8,}$/)
    }
    expect(inDate).toBe('2026-09-01')

    expect(wrapper.find('.arrival-dialog').exists()).toBe(false)
    expect(wrapper.text()).toContain('2件を入庫しました')
    expect(apiMocks.fetchPendingArrivals.mock.calls.at(-1)!.slice(0, 2)).toEqual([null, 1])
    expect(wrapper.find('.arrival-actionbar button').attributes('disabled')).toBeDefined()
  })

  it('on failure keeps the dialog open and retries with the same clientReqIds (7.0 replay)', async () => {
    apiMocks.fetchPendingArrivals.mockResolvedValue(page([row(101, 'HTK9-A1X', 1)]))
    const wrapper = await mountView()
    await waitFor(() => cards(wrapper).length === 1)

    await cards(wrapper)[0]!.trigger('click')
    await wrapper.find('.arrival-actionbar button').trigger('click')

    // 第一次失败（网络错误——请求可能已到达，同键重放是唯一安全路径）
    apiMocks.confirmArrivals.mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR'))
    await wrapper.find('.arrival-dialog-ok').trigger('click')
    await flushPromises()

    expect(wrapper.find('.arrival-dialog').exists()).toBe(true)
    expect(wrapper.text()).toContain('サーバーに接続できません')
    const [firstLines, firstDate] = apiMocks.confirmArrivals.mock.calls[0] as unknown as ConfirmCall
    expect(firstDate).toBeUndefined()

    // 第二次同键重试 → 成功出清
    apiMocks.confirmArrivals.mockResolvedValueOnce({ arrivedCount: 1, items: [] })
    await wrapper.find('.arrival-dialog-ok').trigger('click')
    await flushPromises()

    const [secondLines] = apiMocks.confirmArrivals.mock.calls[1] as unknown as ConfirmCall
    expect(secondLines.map((line) => line.clientReqId)).toEqual(
      firstLines.map((line) => line.clientReqId),
    )
    expect(wrapper.find('.arrival-dialog').exists()).toBe(false)
    expect(wrapper.text()).toContain('1件を入庫しました')
  })

  it('renders a read-only list for viewers (no action bar, cards not selectable)', async () => {
    apiMocks.fetchPendingArrivals.mockResolvedValue(page([row(101, 'HTK9-A1X', 1)]))
    const wrapper = await mountView(3)
    await waitFor(() => cards(wrapper).length === 1)

    expect(wrapper.find('.arrival-actionbar').exists()).toBe(false)
    expect(wrapper.text()).toContain('入庫確認の操作には編集者以上の権限が必要です')
    expect(cards(wrapper)[0]!.attributes('disabled')).toBeDefined()

    await cards(wrapper)[0]!.trigger('click')
    expect(cards(wrapper)[0]!.classes()).not.toContain('is-selected')
    expect(apiMocks.confirmArrivals).not.toHaveBeenCalled()
  })

  it('shows the empty state when nothing awaits arrival', async () => {
    apiMocks.fetchPendingArrivals.mockResolvedValue(page([]))
    const wrapper = await mountView()

    await waitFor(() => wrapper.find('.arrival-empty').exists())
    expect(wrapper.text()).toContain('入庫待ちの商品はありません')
    expect(wrapper.text()).toContain('入庫待ち 0 件')
  })

  it('shows a tappable retry hint when the list fails to load', async () => {
    apiMocks.fetchPendingArrivals.mockRejectedValue(new ApiError(0, 'NETWORK_ERROR'))
    const wrapper = await mountView()

    await waitFor(() => wrapper.find('.van-list__error-text').exists())
    expect(wrapper.find('.van-list__error-text').text()).toContain('読み込みに失敗しました')
  })
})
