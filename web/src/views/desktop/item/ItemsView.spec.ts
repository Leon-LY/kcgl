import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

const apiMocks = vi.hoisted(() => ({
  searchItems: vi.fn(),
  fetchVenues: vi.fn(),
  fetchRecycleBin: vi.fn(),
  restoreItem: vi.fn(),
}))

// ApiError 保持真实实现（错误文案分支依赖 instanceof/code）；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    searchItems: apiMocks.searchItems,
    fetchVenues: apiMocks.fetchVenues,
    fetchRecycleBin: apiMocks.fetchRecycleBin,
    restoreItem: apiMocks.restoreItem,
  }
})

import ItemsView from './ItemsView.vue'
import { i18n } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/utils/api'
import type { ItemSearchRow, ItemSearchResult, MeResponse, RecycleBinRow } from '@/utils/api'

/**
 * 商品一覧（M5-①）：六路筛选搜索（kw/仓库/状态/会场/日期/滞留）+行点击进详情+
 * 滞留黄红徽标+回收站标签（仅管理员，復元回列表）+加载失败重取。
 */

const meAdmin: MeResponse = {
  id: 1,
  username: 'boss',
  displayName: '管理者',
  role: 1,
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

function row(overrides: Partial<ItemSearchRow> = {}): ItemSearchRow {
  return {
    id: 1,
    itemCode: 'HT9-A1X',
    thumbUrl: null,
    itemName: null,
    venueName: '飛騨古美術市',
    buyDate: '2026-01-15',
    purchasePrice: 1000,
    totalCost: 1200,
    profit: null,
    warehouse: 1,
    stockStatus: 0,
    saleStatus: 0,
    soldPrice: null,
    shelfNo: null,
    warehouseInDate: null,
    slowMoveLevel: 0,
    ...overrides,
  }
}

function result(overrides: Partial<ItemSearchResult> = {}): ItemSearchResult {
  return { total: 0, page: 1, size: 20, rows: [], ...overrides }
}

function recycleRow(overrides: Partial<RecycleBinRow> = {}): RecycleBinRow {
  return {
    id: 901,
    itemCode: 'HT9-A9X',
    thumbUrl: null,
    itemName: null,
    venueName: '飛騨古美術市',
    warehouse: 1,
    stockStatus: 1,
    saleStatus: 0,
    voided: false,
    deletedAt: '2026-09-28 15:00:00',
    reason: 'E2E整理',
    ...overrides,
  }
}

async function mountView(role: 1 | 3 = 1): Promise<{ wrapper: VueWrapper; router: Router }> {
  const auth = useAuthStore()
  auth.me = role === 1 ? meAdmin : meViewer
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/items', name: 'items', component: { template: '<div />' } },
      { path: '/items/:id', name: 'item-detail', component: { template: '<div />' } },
    ],
  })
  await router.push({ name: 'items' })
  const wrapper = mount(ItemsView, {
    global: { plugins: [i18n, router] },
  })
  await flushPromises()
  return { wrapper, router }
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  vi.resetAllMocks()
  setActivePinia(createPinia())
  i18n.global.locale.value = 'ja-JP'
  apiMocks.searchItems.mockResolvedValue(result())
  apiMocks.fetchVenues.mockResolvedValue([
    { id: 1, code: 'HT', name: '飛騨古美術市', enabled: true },
    { id: 2, code: 'TK', name: '東京骨董市', enabled: true },
  ])
  apiMocks.fetchRecycleBin.mockResolvedValue(result())
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('items view (M5-1)', () => {
  it('renders list rows with status tags, slow badges, and null-safe venues', async () => {
    apiMocks.searchItems.mockResolvedValue(
      result({
        total: 2,
        rows: [
          row({ id: 1, itemName: '備前茶碗', slowMoveLevel: 2, saleStatus: 2, profit: 8000 }),
          row({ id: 2, itemCode: 'TKK9-A2X', venueName: null, warehouse: 2, stockStatus: 1 }),
        ],
      }),
    )
    const { wrapper } = await mountView()

    const rows = wrapper.findAll('#pane-list .el-table__row')
    expect(rows).toHaveLength(2)
    expect(wrapper.find('.items-count').text()).toBe('全 2 件')
    expect(rows[0]!.text()).toContain('HT9-A1X')
    expect(rows[0]!.text()).toContain('長期滞留')
    expect(rows[0]!.find('.items-tag.is-success').exists()).toBe(true) // 落札済み=green
    // venueName null → 占位符；仓 2 名古屋→福岡标签映射
    expect(rows[1]!.text()).toContain('—')
    expect(rows[1]!.text()).toContain('福岡倉庫')
    expect(rows[0]!.find('.items-tag.is-danger').exists()).toBe(true)
    expect(rows[0]!.text()).toContain('￥8,000')
  })

  // 回归（D-056）：el-table-column 以 {row:{}} 探测嵌套列时逐列调用默认插槽
  // （TableColumnRenderer），动态 i18n key 必须空值兜底——否则空数据页也刷
  // common.warehouse.undefined / scan.stock.undefined 告警（E2E 实录）。
  it('renders warehouse and status cells without missing-key warnings on the probe pass', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const { wrapper } = await mountView(3)
    expect(wrapper.exists()).toBe(true)
    // mockRestore 会清空 mock.calls——先快照再还原
    const warnings = warnSpy.mock.calls.map((call) => String(call[0]))
    warnSpy.mockRestore()
    expect(warnings.filter((message) => message.includes('common.warehouse'))).toHaveLength(0)
    expect(warnings.filter((message) => message.includes('scan.stock'))).toHaveLength(0)
    expect(warnings.filter((message) => message.includes('scan.sale'))).toHaveLength(0)
  })

  it('sends keyword search and warehouse filter to the API with page reset', async () => {
    const { wrapper } = await mountView()
    expect(apiMocks.searchItems).toHaveBeenCalledTimes(1)

    // kw 输入 → 検索按钮：trim 后携带
    const kwInput = wrapper.find('.items-search input')
    await kwInput.setValue('  HT9-A1X ')
    await wrapper.findAll('button').find((b) => b.text() === '検索')!.trigger('click')
    await flushPromises()
    expect(apiMocks.searchItems).toHaveBeenLastCalledWith(
      expect.objectContaining({ kw: 'HT9-A1X', page: 1, size: 20 }),
    )

    // 仓库下拉变更（el-select v-model + @change 双事件模拟）→ 立即搜索
    const selects = wrapper.findAllComponents({ name: 'ElSelect' })
    await selects[0]!.vm.$emit('update:modelValue', 2)
    await selects[0]!.vm.$emit('change', 2)
    await flushPromises()
    expect(apiMocks.searchItems).toHaveBeenLastCalledWith(
      expect.objectContaining({ warehouse: 2, page: 1 }),
    )

    // 条件クリア：全部复位回第一页全量
    await wrapper.findAll('button').find((b) => b.text() === '条件をクリア')!.trigger('click')
    await flushPromises()
    expect(apiMocks.searchItems).toHaveBeenLastCalledWith(
      expect.objectContaining({ kw: undefined, warehouse: undefined, page: 1 }),
    )
  })

  it('navigates to the detail route on row click', async () => {
    apiMocks.searchItems.mockResolvedValue(result({ rows: [row({ id: 5 })] }))
    const { wrapper, router } = await mountView()

    const table = wrapper.findComponent({ name: 'ElTable' })
    table.vm.$emit('row-click', row({ id: 5 }))
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('item-detail')
    expect(router.currentRoute.value.params.id).toBe('5')
  })

  it('admin sees the recycle tab and restores items; viewer has no tab', async () => {
    apiMocks.fetchRecycleBin.mockResolvedValue(result({ total: 1, rows: [recycleRow()] }))
    const { wrapper } = await mountView()

    expect(wrapper.find('#pane-recycle').exists()).toBe(true)
    const recycleRow_ = wrapper.find('#pane-recycle .el-table__row')
    expect(recycleRow_.text()).toContain('HT9-A9X')
    expect(recycleRow_.text()).toContain('E2E整理')

    apiMocks.restoreItem.mockResolvedValue(recycleRow({}))
    await wrapper
      .findAll('#pane-recycle button')
      .find((b) => b.text() === '復元する')!
      .trigger('click')
    await flushPromises()

    expect(apiMocks.restoreItem).toHaveBeenCalledWith(901, expect.any(String))
    expect(apiMocks.fetchRecycleBin).toHaveBeenCalledTimes(2)

    // viewer：单标签无回收站
    const viewer = await mountView(3)
    expect(viewer.wrapper.find('#pane-recycle').exists()).toBe(false)
    expect(viewer.wrapper.findAll('.el-tabs__item')).toHaveLength(1)
  })

  it('shows a load error box with reload for both panes', async () => {
    apiMocks.searchItems.mockRejectedValueOnce(new ApiError(0, 'network down'))
    apiMocks.fetchRecycleBin.mockRejectedValueOnce(new ApiError(0, 'network down'))
    const { wrapper } = await mountView()

    const listBox = wrapper.find('#pane-list .kcgl-error-box')
    expect(listBox.text()).toContain('network down')
    expect(wrapper.find('#pane-recycle .kcgl-error-box').text()).toContain('network down')

    apiMocks.searchItems.mockResolvedValue(result())
    apiMocks.fetchRecycleBin.mockResolvedValue(result())
    await listBox.find('button').trigger('click')
    await flushPromises()
    expect(wrapper.find('#pane-list .kcgl-error-box').exists()).toBe(false)
  })
})
