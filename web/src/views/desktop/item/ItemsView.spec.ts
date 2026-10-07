import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

const apiMocks = vi.hoisted(() => ({
  searchItems: vi.fn(),
  fetchVenues: vi.fn(),
  fetchRecycleBin: vi.fn(),
  restoreItem: vi.fn(),
  sellItem: vi.fn(),
  scrapItem: vi.fn(),
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
    sellItem: apiMocks.sellItem,
    scrapItem: apiMocks.scrapItem,
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

const meEditor: MeResponse = {
  id: 2,
  username: 'shigoto',
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

async function mountView(
  role: 1 | 2 | 3 = 1,
  query: Record<string, string> = {},
): Promise<{ wrapper: VueWrapper; router: Router }> {
  const auth = useAuthStore()
  auth.me = role === 1 ? meAdmin : role === 2 ? meEditor : meViewer
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/items', name: 'items', component: { template: '<div />' } },
      { path: '/items/:id', name: 'item-detail', component: { template: '<div />' } },
      { path: '/excel', name: 'excel', component: { template: '<div />' } },
    ],
  })
  // 先落一站「上一页」，再进列表：列表态投影用 replace 写 URL，后退一步应直接离开列表
  // （C1 的判定依据）；内存历史只有一条记录时 back() 无处可去，测不出 push/replace 之别
  await router.push({ name: 'home' })
  await router.push({ name: 'items', query })
  const wrapper = mount(ItemsView, {
    global: { plugins: [i18n, router] },
  })

  await flushPromises()
  return { wrapper, router }
}

enableAutoUnmount(afterEach)

/**
 * 覆写媒体查询桩：按查询里的 min-width 与给定视口宽比较（与真实浏览器同判）。
 * 默认桩恒 false（=最窄档），列分档的用例必须显式声明视口，否则只测到窄屏形态。
 */
function stubViewport(width: number): void {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation((query: string) => {
      const min = /min-width:\s*(\d+)px/.exec(query)
      return {
        matches: min != null && width >= Number(min[1]),
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(),
      }
    }),
  })
}

beforeEach(() => {
  vi.resetAllMocks()
  // 默认按 24 吋（1920）宽屏挂载：与客户现场一致，也让既有断言看到全部列
  stubViewport(1920)
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

/**
 * 一括入出力入口（D-106）：批量导入/导出本来只在侧栏「Excel」下，商品页找不到；
 * 入口收到本页工具栏，仍落到 /excel 的对应标签页（批次历史与报告留在原页）。
 */
describe('items bulk import/export entry (D-106)', () => {
  it('routes to the excel import tab from the toolbar menu', async () => {
    const { wrapper, router } = await mountView()

    const dropdown = wrapper.findComponent({ name: 'ElDropdown' })
    expect(dropdown.exists()).toBe(true)

    dropdown.vm.$emit('command', 'import')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('excel')
    expect(router.currentRoute.value.query.tab).toBe('import')
  })

  it('routes to the excel export tab from the toolbar menu', async () => {
    const { wrapper, router } = await mountView()

    wrapper.findComponent({ name: 'ElDropdown' }).vm.$emit('command', 'export')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('excel')
    expect(router.currentRoute.value.query.tab).toBe('export')
  })
})

/**
 * 列表态 ↔ URL 查询串（审计项 C1）：详情下钻一趟回来不该丢筛选与页码。
 * 列表把**实际发出的检索参数**投影进 query（唯一写入点在 loadList，与请求同源，
 * 不会出现「URL 带筛选而列表没有」），首载按 query 还原；默认态保持 /items 裸路径。
 */
describe('items list state in the URL (C1)', () => {
  beforeEach(() => {
    apiMocks.searchItems.mockResolvedValue(result({ total: 60 }))
  })

  it('hydrates filters and the page from the query on mount', async () => {
    const { router } = await mountView(1, { kw: 'HT9', warehouse: '2', page: '3' })

    expect(apiMocks.searchItems).toHaveBeenCalledWith(
      expect.objectContaining({ kw: 'HT9', warehouse: 2, page: 3, size: 20 }),
    )
    expect(router.currentRoute.value.query).toEqual({ kw: 'HT9', warehouse: '2', page: '3' })
  })

  it('treats out-of-domain or malformed query values as unset', async () => {
    await mountView(1, {
      warehouse: '9',
      stockStatus: 'abc',
      saleStatus: '-1',
      venueId: '0',
      warnLevel: '3',
      buyDateFrom: '2026/09/01',
      page: '0',
    })

    expect(apiMocks.searchItems).toHaveBeenCalledWith(
      expect.objectContaining({
        warehouse: undefined,
        stockStatus: undefined,
        saleStatus: undefined,
        venueId: undefined,
        warnLevel: undefined,
        buyDateFrom: undefined,
        page: 1,
      }),
    )
  })

  it('keeps the bare path while the list is at its default state', async () => {
    // E2E 断言 /\/items$/（削除済み商品の遷移先）——默认态不得凭空多出 query
    const { wrapper, router } = await mountView()
    expect(router.currentRoute.value.fullPath).toBe('/items')

    // 未按「検索」的输入不算列表态：URL 只投影已发出的检索参数
    await wrapper.find('.items-search input').setValue('HT9-A1X')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/items')
  })

  it('projects the applied keyword and page into the query', async () => {
    const { wrapper, router } = await mountView()

    await wrapper.find('.items-search input').setValue('  HT9-A1X ')
    await wrapper.findAll('button').find((b) => b.text() === '検索')!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.kw).toBe('HT9-A1X')

    wrapper.findComponent({ name: 'ElPagination' }).vm.$emit('current-change', 2)
    await flushPromises()
    expect(apiMocks.searchItems).toHaveBeenLastCalledWith(
      expect.objectContaining({ kw: 'HT9-A1X', page: 2 }),
    )
    expect(router.currentRoute.value.query).toEqual({ kw: 'HT9-A1X', page: '2' })

    // 条件クリア → 回到默认态即抹掉 query
    await wrapper.findAll('button').find((b) => b.text() === '条件をクリア')!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/items')
  })

  it('writes filter changes with replace so the back key leaves the list in one step', async () => {
    const { wrapper, router } = await mountView()

    await wrapper.find('.items-search input').setValue('HT9-A1X')
    await wrapper.findAll('button').find((b) => b.text() === '検索')!.trigger('click')
    await flushPromises()

    // 若筛选变更走了 push，此处后退会退回「上一个筛选态」（仍是 items）；
    // replace 则一步退出列表，回到进入列表前的上一站
    await router.back()
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('home')
  })
})

/**
 * 列布局（D-126，取代 D-120 的视口分档）：**全列常显 + 左右钉列 + 中间横滑**。
 *
 * D-120 按视口收列（窄屏藏起落札日/棚番号/滞留/会場/倉庫），D-126 去掉整套分档：
 * 收列等于把信息藏起来，而先被收掉的几列恰好都有对应筛选器，用户在表上看见的与
 * 筛选器能问的不一致。改为全列常显，由 el-table 的 fixed 列（sticky 实现）钉住
 * 身份列与动作列，中间列自行横滑；宽屏放得下时本就不出滚动条，故非"窄屏降级"。
 *
 * 这里锁两条：① 任何视口都渲染全部列（列不会因视口而消失）；② 钉的是哪几列。
 */
describe('items view column layout (D-126)', () => {
  interface Col {
    label: string | undefined
    type: string | undefined
    width: number
    fixed: string | undefined
  }

  /** 指定标签页的声明列：文案 / 类型 / 声明列宽（width 缺省时落 min-width）/ 钉向。
   *  width 为空串时（EP 的 prop 默认值）落到 min-width；用 || 而非 ?? 正是为此。
   *  fixed 同理：EP 的默认值是 false，未声明钉向的列读到的是 false 而非 undefined，
   *  这里归一成 undefined，让「钉向」只有 left/right/未钉三种取值。 */
  function paneColumns(wrapper: VueWrapper, pane: string): Col[] {
    return wrapper
      .find(pane)
      .findAllComponents({ name: 'ElTableColumn' })
      .map((col) => ({
        label: col.props('label') as string | undefined,
        type: col.props('type') as string | undefined,
        width: Number(col.props('width')) || Number(col.props('minWidth')) || 0,
        fixed: (col.props('fixed') as string | boolean | undefined) || undefined,
      }))
  }

  const sumOf = (cols: Col[]): number => cols.reduce((n, col) => n + col.width, 0)

  it('窄屏 1240 下主列表仍是全 13 列——不再按视口收列', async () => {
    stubViewport(1240)
    const { wrapper } = await mountView()

    const cols = paneColumns(wrapper, '#pane-list')
    expect(cols).toHaveLength(13)
    expect(cols.map((c) => c.label)).toEqual(
      expect.arrayContaining(['落札日', '棚番号', '滞留', '会場', '倉庫', '状態', '操作']),
    )
    // 列宽合计仍是实测的自然宽之和（「操作」列 D-129 由 64 改 96，装得下动作菜单的
    // 触发器）：宽度与"收不收列"无关，只与内容放不放得下有关
    expect(sumOf(cols)).toBe(1438)
  })

  it('主列表左钉「勾选 + 商品」、右钉「状態 + 操作」', async () => {
    stubViewport(1240)
    const { wrapper } = await mountView()

    const cols = paneColumns(wrapper, '#pane-list')
    expect(cols.filter((c) => c.fixed === 'left')).toEqual([
      expect.objectContaining({ type: 'selection' }),
      expect.objectContaining({ label: '商品' }),
    ])
    expect(cols.filter((c) => c.fixed === 'right').map((c) => c.label)).toEqual(['状態', '操作'])
  })

  it('回收站左钉「勾选 + 商品」、右钉「操作」；「状態」不钉（它不紧邻右缘）', async () => {
    stubViewport(1240)
    const { wrapper } = await mountView()

    const cols = paneColumns(wrapper, '#pane-recycle')
    expect(cols.filter((c) => c.fixed === 'left')).toEqual([
      expect.objectContaining({ type: 'selection' }),
      expect.objectContaining({ label: '商品' }),
    ])
    expect(cols.filter((c) => c.fixed === 'right')).toHaveLength(1)
    expect(cols.find((c) => c.label === '状態')?.fixed).toBeUndefined()
  })
})

/**
 * 行内状态动作（D-129）：列表页直接改状态，合法动作由 availableActions 按现行两轴
 * 派生（与扫码页同一张边表），管理员多出「状態修正」（任意态覆盖）与「削除」。
 * 菜单项以 command 为契约断言——文案由 i18n 键给，点下去做什么由 command 决定。
 */
describe('items row status actions (D-129)', () => {
  /** 行内菜单项文案：菜单渲染在 body 的浮层里，不在 wrapper 树内。 */
  function menuItems(): string[] {
    return [...document.body.querySelectorAll('.el-dropdown-menu__item')].map(
      (el) => el.textContent?.trim() ?? '',
    )
  }

  /** 工具栏也有一个 ElDropdown，故按行取而不是按序号取。 */
  function rowDropdown(wrapper: VueWrapper, itemCode: string) {
    const target = wrapper
      .findAll('#pane-list .el-table__row')
      .find((r) => r.text().includes(itemCode))
    return target?.findComponent({ name: 'ElDropdown' })
  }

  /**
   * 取行内动作弹层的按钮。**不能**用 `wrapper.findComponent({name:'ElDialog'})`：
   * 页面上还有一括削除的弹层（未打开时 el-dialog 什么都不渲染），按组件类型取第一个
   * 拿到的是它。以本弹层独有的 `.itemact-form` 定位到 el-dialog 元素再取页脚按钮。
   */
  function actionDialogButton(wrapper: VueWrapper, label: string): HTMLButtonElement {
    const dialog = wrapper.find('.itemact-form').element.closest('.el-dialog')
    expect(dialog).not.toBeNull()
    const button = [...dialog!.querySelectorAll<HTMLButtonElement>('.el-dialog__footer button')]
      .find((b) => b.textContent?.trim() === label)
    expect(button).toBeDefined()
    return button!
  }

  function clickActionDialog(wrapper: VueWrapper, label: string): Promise<void> {
    actionDialogButton(wrapper, label).click()
    return flushPromises()
  }

  beforeEach(() => {
    // 1 件在庫・未上架：合法动作＝出品中として記録／廃棄／移動 等
    apiMocks.searchItems.mockResolvedValue(
      result({ total: 1, rows: [row({ stockStatus: 1, saleStatus: 0 })] }),
    )
  })

  it('管理员菜单＝合法动作 + 状態修正 + 削除', async () => {
    const { wrapper } = await mountView(1)
    expect(rowDropdown(wrapper, 'HT9-A1X')?.exists()).toBe(true)

    // 在庫・未上架 的合法动作按现场频率：売却/移動/廃棄/出品済みにする/会場へ返す
    expect(menuItems()).toEqual([
      'インポート',
      'エクスポート',
      '売却',
      '移動',
      '廃棄',
      '出品済みにする',
      '会場へ返す',
      '在庫状態・販売状態の修正',
      '削除',
    ])
  })

  it('编辑者只拿到合法动作，没有削除与状態修正（两者都是 A-only）', async () => {
    const { wrapper } = await mountView(2)
    expect(rowDropdown(wrapper, 'HT9-A1X')?.exists()).toBe(true)

    const items = menuItems()
    expect(items).toContain('廃棄')
    expect(items).not.toContain('削除')
    expect(items).not.toContain('在庫状態・販売状態の修正')
  })

  it('浏览者没有操作列，也不该看到操作入口', async () => {
    const { wrapper } = await mountView(3)
    expect(rowDropdown(wrapper, 'HT9-A1X')?.exists()).toBe(false)
    expect(wrapper.findAll('#pane-list .items-actions')).toHaveLength(0)
  })

  it('菜单里选一个动作即打开弹层，确认后按幂等键提交并刷新列表', async () => {
    apiMocks.sellItem.mockResolvedValue({
      itemId: 1,
      itemCode: 'HT9-A1X',
      stockStatus: 2,
      saleStatus: 2,
      warehouse: 1,
    })
    const { wrapper } = await mountView(1)
    const listCalls = apiMocks.searchItems.mock.calls.length

    rowDropdown(wrapper, 'HT9-A1X')!.vm.$emit('command', 'sell')
    await flushPromises()

    // 落札価格は任意項目：空のままでも落札として記録できる
    await clickActionDialog(wrapper, '売却する')

    expect(apiMocks.sellItem).toHaveBeenCalledTimes(1)
    const [itemId, clientReqId, soldPrice] = apiMocks.sellItem.mock.calls[0]!
    expect(itemId).toBe(1)
    // 幂等键必须由前端生成（http 部署下 crypto.randomUUID 不存在，见 D-125）
    expect(typeof clientReqId).toBe('string')
    expect(clientReqId.length).toBeGreaterThan(0)
    expect(soldPrice).toBeUndefined()
    // 列表重取 + 回执（这一行可能因筛选不再命中而整行消失，回执是唯一落点）
    expect(apiMocks.searchItems.mock.calls.length).toBeGreaterThan(listCalls)
    const notice = wrapper.find('#pane-list .items-batch-result')
    expect(notice.text()).toContain('HT9-A1X')
    expect(notice.text()).toContain('売却を記録しました')
  })

  it('报废未填理由时不发请求，错误留在弹层里（弹层不关）', async () => {
    const { wrapper } = await mountView(1)

    rowDropdown(wrapper, 'HT9-A1X')!.vm.$emit('command', 'scrap')
    await flushPromises()
    await clickActionDialog(wrapper, '廃棄する')

    expect(apiMocks.scrapItem).not.toHaveBeenCalled()
    // 弹层保持打开：直接重试同键即可安全重放（docs/01 7.0）
    expect(wrapper.find('.itemact-form').exists()).toBe(true)
    expect(wrapper.find('.itemact-form .kcgl-error-box').text()).toContain(
      '廃棄理由を入力してください',
    )
  })
})
