import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  fetchStocktake: vi.fn(),
  fetchStocktakeDiffs: vi.fn(),
  resolveStocktakeDiff: vi.fn(),
}))

// ApiError 保持真实实现（错误文案分支依赖真实 toDisplayMessage）；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchStocktake: apiMocks.fetchStocktake,
    fetchStocktakeDiffs: apiMocks.fetchStocktakeDiffs,
    resolveStocktakeDiff: apiMocks.resolveStocktakeDiff,
  }
})

// 失效接线捕获：直接持有视图注册的 reload 回调，模拟 SSE 失效落点
// （不连真实 sync store——组件级只验证「回声落进 armed 窗口不得劫持确认」）
const syncMock = vi.hoisted(() => ({ reload: null as (() => void) | null }))

vi.mock('@/composables/useSyncInvalidation', () => ({
  useSyncInvalidation: (_types: readonly string[], reload: () => void) => {
    syncMock.reload = reload
  },
}))

const routeMock = vi.hoisted(() => ({ params: { id: '5' } }))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => routeMock,
  }
})

import StocktakeDiffView from './StocktakeDiffView.vue'
import { i18n } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/utils/api'
import type { MeResponse, StocktakeDiffList, StocktakeDiffRow, StocktakeSummary } from '@/utils/api'

/**
 * 差异确认页（M3-⑥）：行卡渲染（类型标签/仓库迁移/变动标注/类型化提示）/
 * 默认待确认筛选与切换/行内两步 CONFIRM（幂等键失败重试复用同键 docs/01 7.0）/
 * 冻结品禁 CONFIRM 仅可忽略/IGNORE 置位/全部处理完 allDone/viewer 只读。
 */

type ResolveCall = [number, number, 'CONFIRM' | 'IGNORE', string]

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

function summary(overrides: Partial<StocktakeSummary> = {}): StocktakeSummary {
  return {
    id: 5,
    stocktakeNo: 'PD20260928-01',
    warehouse: 1,
    status: 1,
    expectedCount: 10,
    scannedCount: 8,
    diffCount: 3,
    pendingDiffCount: 3,
    createdAt: '2026-09-28 09:00:00',
    closedAt: '2026-09-28 12:00:00',
    createdByName: '編集者',
    closedByName: '編集者',
    mine: true,
    ...overrides,
  }
}

function diffRow(overrides: Partial<StocktakeDiffRow> = {}): StocktakeDiffRow {
  return {
    id: 11,
    itemId: 201,
    itemCode: 'HTK9-A1X',
    diffType: 1,
    expectedWarehouse: 1,
    actualWarehouse: null,
    note: null,
    confirmStatus: 0,
    thumbUrl: null,
    ...overrides,
  }
}

function diffList(rows: StocktakeDiffRow[], total?: number): StocktakeDiffList {
  return { total: total ?? rows.length, page: 1, size: rows.length, rows }
}

function rows(wrapper: VueWrapper) {
  return wrapper.findAll('.diff-row')
}

/** 行内按钮按文案定位（两步确认会替换按钮组，class 不稳）。 */
function rowButton(row: ReturnType<typeof rows>[number], text: string) {
  return row.findAll('button').find((button) => button.text().includes(text))
}

async function mountView(role: 2 | 3 = 2): Promise<VueWrapper> {
  const auth = useAuthStore()
  auth.me = role === 2 ? meEditor : meViewer
  const wrapper = mount(StocktakeDiffView, {
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

/** jsdom 布局最小桩：Vant List 的 check() 以视口高度>0 为触发前提（D-038）。 */
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

describe('stocktake difference review (M3-6)', () => {
  it('renders diff rows with type tags, warehouse moves, and change notes', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(summary())
    apiMocks.fetchStocktakeDiffs.mockResolvedValue(
      diffList([
        diffRow({ id: 11, diffType: 1 }),
        diffRow({ id: 12, diffType: 2 }),
        diffRow({ id: 13, diffType: 3, expectedWarehouse: 1, actualWarehouse: 2, note: 'changed during stocktake' }),
      ]),
    )
    const wrapper = await mountView()

    await waitFor(() => rows(wrapper).length === 3)
    expect(wrapper.text()).toContain('未確認 3 件')
    expect(wrapper.text()).toContain('在庫不足')
    expect(wrapper.text()).toContain('在庫超過')
    expect(wrapper.text()).toContain('倉庫違い')
    expect(wrapper.text()).toContain('名古屋倉庫 → 福岡倉庫')
    // 仓库迁移行带「盘点期间变动」标注（辅助裁决）；loss/wh 各自的类型化提示
    expect(wrapper.text()).toContain('棚卸期間中にこの商品の変動がありました')
    expect(wrapper.text()).toContain('スキャンされなかった商品です')
    expect(wrapper.text()).toContain('別倉庫の在庫です')
  })

  it('loads pending diffs by default and refetches page 1 when the filter switches', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(summary())
    apiMocks.fetchStocktakeDiffs.mockResolvedValue(diffList([diffRow({ id: 11 })]))
    const wrapper = await mountView()
    await waitFor(() => rows(wrapper).length === 1)

    expect(apiMocks.fetchStocktakeDiffs).toHaveBeenLastCalledWith(5, 1, 20, 0)

    await wrapper.findAll('.diff-filter-option')[1]!.trigger('click')
    await waitFor(() => apiMocks.fetchStocktakeDiffs.mock.calls.length === 2)
    expect(apiMocks.fetchStocktakeDiffs).toHaveBeenLastCalledWith(5, 1, 20, undefined)
    expect(wrapper.findAll('.diff-filter-option')[1]!.classes()).toContain('is-active')
  })

  it('confirms a diff through the two-step flow and updates the row in place', async () => {
    // 首次载入=1 条待确认；最后一条处理完视图重读摘要，此时服务端已自动转已确认
    // （status 2/pendingDiffCount 0）——第二次取回终态驱动 allDone 横幅（全链路
    // 行为，非纯本地扣减）
    apiMocks.fetchStocktake
      .mockResolvedValueOnce(summary({ pendingDiffCount: 1 }))
      .mockResolvedValueOnce(summary({ status: 2, pendingDiffCount: 0 }))
    apiMocks.fetchStocktakeDiffs.mockResolvedValue(diffList([diffRow({ id: 11 })]))
    apiMocks.resolveStocktakeDiff.mockResolvedValue({
      diff: diffRow({ id: 11, confirmStatus: 1 }),
      result: null,
    })
    const wrapper = await mountView()
    await waitFor(() => rows(wrapper).length === 1)
    const row = rows(wrapper)[0]!

    // 第一步：確認 → 行内二次确认；いいえ 可退回
    await rowButton(row, '調整する')!.trigger('click')
    expect(row.text()).toContain('実行しますか')
    await rowButton(row, 'いいえ')!.trigger('click')
    expect(rowButton(row, '調整する')).toBeDefined()

    // 第二步：はい → CONFIRM 落库 → 行置为調整済み、待确认计数-1
    await rowButton(row, '調整する')!.trigger('click')
    await rowButton(row, 'はい')!.trigger('click')
    await flushPromises()

    expect(apiMocks.resolveStocktakeDiff).toHaveBeenCalledTimes(1)
    const [stocktakeId, diffId, action] = apiMocks.resolveStocktakeDiff.mock.calls[0] as unknown as ResolveCall
    expect([stocktakeId, diffId, action]).toEqual([5, 11, 'CONFIRM'])
    expect(row.text()).toContain('調整済み')
    expect(rowButton(row, '調整する')).toBeUndefined()
    expect(apiMocks.fetchStocktake).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('未確認 0 件')
    expect(wrapper.find('.diff-alldone').exists()).toBe(true)
  })

  it('retries a failed confirm with the same clientReqId (7.0 replay)', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(summary({ pendingDiffCount: 1 }))
    apiMocks.fetchStocktakeDiffs.mockResolvedValue(diffList([diffRow({ id: 11 })]))
    apiMocks.resolveStocktakeDiff
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR'))
      .mockResolvedValueOnce({ diff: diffRow({ id: 11, confirmStatus: 1 }), result: null })
    const wrapper = await mountView()
    await waitFor(() => rows(wrapper).length === 1)
    const row = rows(wrapper)[0]!

    await rowButton(row, '調整する')!.trigger('click')
    await rowButton(row, 'はい')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.diff-action-error').text()).toContain('サーバーに接続できません')
    // 失败后 armed 不复位：直接再点「はい」重试，且复用同一幂等键
    expect(rowButton(row, 'はい')).toBeDefined()

    await rowButton(row, 'はい')!.trigger('click')
    await flushPromises()

    const firstKey = (apiMocks.resolveStocktakeDiff.mock.calls[0] as unknown as ResolveCall)[3]
    const secondKey = (apiMocks.resolveStocktakeDiff.mock.calls[1] as unknown as ResolveCall)[3]
    expect(secondKey).toBe(firstKey)
    expect(row.text()).toContain('調整済み')
  })

  it('hides the confirm button for frozen items and hints at offline handling', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(summary({ pendingDiffCount: 1 }))
    apiMocks.fetchStocktakeDiffs.mockResolvedValue(diffList([diffRow({ id: 14, diffType: 4 })]))
    const wrapper = await mountView()
    await waitFor(() => rows(wrapper).length === 1)
    const row = rows(wrapper)[0]!

    expect(rowButton(row, '調整する')).toBeUndefined()
    expect(rowButton(row, '無視する')).toBeDefined()
    expect(row.text()).toContain('取り消し済み・削除済みの商品は調整できません')
  })

  it('ignores a diff and marks the row as ignored', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(summary({ pendingDiffCount: 1 }))
    apiMocks.fetchStocktakeDiffs.mockResolvedValue(diffList([diffRow({ id: 11 })]))
    apiMocks.resolveStocktakeDiff.mockResolvedValue({
      diff: diffRow({ id: 11, confirmStatus: 2 }),
      result: null,
    })
    const wrapper = await mountView()
    await waitFor(() => rows(wrapper).length === 1)
    const row = rows(wrapper)[0]!

    await rowButton(row, '無視する')!.trigger('click')
    await flushPromises()

    const [stocktakeId, diffId, action] = apiMocks.resolveStocktakeDiff.mock.calls[0] as unknown as ResolveCall
    expect([stocktakeId, diffId, action]).toEqual([5, 11, 'IGNORE'])
    expect(row.text()).toContain('無視')
    expect(rowButton(row, '無視する')).toBeUndefined()
  })

  it('shows the all-done banner for a fully confirmed stocktake', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(summary({ status: 2, pendingDiffCount: 0, diffCount: 0 }))
    apiMocks.fetchStocktakeDiffs.mockResolvedValue(diffList([]))
    const wrapper = await mountView()

    await waitFor(() => wrapper.find('.diff-alldone').exists())
    expect(wrapper.text()).toContain('すべての差異を処理しました')
    expect(wrapper.text()).toContain('差異はありません')
  })

  it('keeps an armed two-step confirm intact when an invalidation lands mid-confirm', async () => {
    // 回归（E2E stocktake 端到端 30s 超时根因）：close/扫描的 STOCKTAKE 回声经
    // 500ms 防抖落进用户已拉开两步确认的窗口——armed 模板被整页重取拆掉，
    // 「はい」按钮从 DOM 消失且不会自行回来（盘点多人并发下同事扫码同样触发）。
    // 守卫语义：armed/busy 期间跳过失效重取；本人确认动作的回声在 busy 解除后
    // 追平远端变化，armed 释放后失效路径照常工作。
    apiMocks.fetchStocktake.mockResolvedValue(summary({ pendingDiffCount: 2 }))
    apiMocks.fetchStocktakeDiffs.mockResolvedValue(
      diffList([diffRow({ id: 11 }), diffRow({ id: 12, itemId: 202 })]),
    )
    const wrapper = await mountView()
    await waitFor(() => rows(wrapper).length === 2)
    const callsBefore = apiMocks.fetchStocktakeDiffs.mock.calls.length
    const summaryCallsBefore = apiMocks.fetchStocktake.mock.calls.length

    const row = rows(wrapper)[0]!
    await rowButton(row, '調整する')!.trigger('click')
    expect(row.text()).toContain('実行しますか')

    // 失效落在 armed 窗口内：はい 按钮必须在场，且不触发任何重取
    syncMock.reload!()
    await flushPromises()
    expect(rowButton(row, 'はい')).toBeDefined()
    expect(apiMocks.fetchStocktakeDiffs.mock.calls.length).toBe(callsBefore)
    expect(apiMocks.fetchStocktake.mock.calls.length).toBe(summaryCallsBefore)

    // いいえ 解除 armed 后，同样的失效照常重取（远端变化追平路径不变）
    await rowButton(row, 'いいえ')!.trigger('click')
    syncMock.reload!()
    await waitFor(() => apiMocks.fetchStocktakeDiffs.mock.calls.length > callsBefore)
  })

  it('renders read-only rows for viewers', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(summary({ pendingDiffCount: 1 }))
    apiMocks.fetchStocktakeDiffs.mockResolvedValue(diffList([diffRow({ id: 11 })]))
    const wrapper = await mountView(3)
    await waitFor(() => rows(wrapper).length === 1)

    const row = rows(wrapper)[0]!
    expect(rowButton(row, '調整する')).toBeUndefined()
    expect(rowButton(row, '無視する')).toBeUndefined()
    expect(apiMocks.resolveStocktakeDiff).not.toHaveBeenCalled()
  })
})
