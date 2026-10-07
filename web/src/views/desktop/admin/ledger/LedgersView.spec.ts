import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

const apiMocks = vi.hoisted(() => ({
  fetchLedgers: vi.fn(),
}))

// ApiError/errors 保持真实实现；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchLedgers: apiMocks.fetchLedgers,
  }
})

import LedgersView from './LedgersView.vue'
import { i18n } from '@/i18n'
import { ApiError } from '@/utils/api'
import type { LedgerBrowseResult, LedgerRow } from '@/utils/api'

/**
 * 台帳ブラウズ（M5-④）：管理员全库流水翻查——筛选（类型/仓库/前缀/操作人/
 * 时间窗）→ fetchLedgers 参数组装（闭开区间 T00:00:00 边界）→ id 倒序分页 →
 * 行点击进详情 → 加载失败重取。
 */

function row(overrides: Partial<LedgerRow> = {}): LedgerRow {
  return {
    id: 101,
    itemId: 11,
    itemCode: 'HTK5-A1X',
    txnType: 5,
    whFrom: 1,
    whTo: 2,
    qtyChange: 0,
    stockFrom: 1,
    stockTo: 1,
    saleFrom: null,
    saleTo: null,
    returnDirection: null,
    refType: null,
    refId: null,
    reason: '整理のため',
    clientReqId: 'req-1',
    operatorName: '編集者',
    createdAt: '2026-09-28 10:02:00',
    ...overrides,
  }
}

function result(overrides: Partial<LedgerBrowseResult> = {}): LedgerBrowseResult {
  return { total: 0, page: 1, size: 20, rows: [], ...overrides }
}

async function mountView(): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/ledgers', name: 'ledgers', component: { template: '<div />' } },
      { path: '/items/:id', name: 'item-detail', component: { template: '<div />' } },
    ],
  })
  await router.push({ name: 'ledgers' })
  const wrapper = mount(LedgersView, {
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
  apiMocks.fetchLedgers.mockResolvedValue(result())
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('ledgers view (M5-4)', () => {
  it('loads page 1 unfiltered and renders rows with type labels and transitions', async () => {
    apiMocks.fetchLedgers.mockResolvedValue(
      result({
        total: 2,
        rows: [
          // 真实 CREATE 形状（ItemCodeTxService.buildCreateLedger）：
          // 在途态不占仓账——wh 双侧 NULL、件数 0、库存 —→0
          row({ id: 2, txnType: 1, whFrom: null, whTo: null, qtyChange: 0, stockFrom: null, stockTo: 0, reason: null }),
          row({ id: 1 }),
        ],
      }),
    )
    const { wrapper } = await mountView()

    expect(apiMocks.fetchLedgers).toHaveBeenCalledWith({
      txnType: undefined,
      itemCode: undefined,
      operatorName: undefined,
      warehouse: undefined,
      dateFrom: undefined,
      dateTo: undefined,
      page: 1,
      size: 20,
    })
    const rows = wrapper.findAll('.el-table__row')
    expect(rows).toHaveLength(2)
    expect(wrapper.find('.ledgers-count').text()).toBe('2 件')
    // txnType 1 → 新規登録；CREATE 行仓列双侧空占位（无箭头，登记语义）
    expect(rows[0]!.text()).toContain('HTK5-A1X')
    expect(rows[0]!.text()).toContain('新規登録')
    const createWhCell = rows[0]!.find('.ledgers-warehouse').text()
    expect(createWhCell).toContain('—')
    expect(createWhCell).not.toContain('→')
    // txnType 5 → 倉庫移動 名古屋→福岡 双侧；qty 0 不带正号
    expect(rows[1]!.text()).toContain('倉庫移動')
    expect(rows[1]!.text()).toContain('名古屋')
    expect(rows[1]!.text()).toContain('福岡')
    expect(rows[1]!.text()).toContain('整理のため')
    expect(rows[1]!.text()).toContain('編集者')
  })

  it('renders system-generated reasons in the active language, keeping manual ones as-is', async () => {
    apiMocks.fetchLedgers.mockResolvedValue(
      result({
        total: 2,
        rows: [
          // 盘点差异入账（V7，D-130）：键+参数齐备，按当前语言渲染
          row({
            id: 2,
            txnType: 7,
            reason: '棚卸調整 PD2026100101',
            reasonCode: 'ledgers.reason.stocktakeAdjust',
            reasonParams: '{"no":"PD2026100101"}',
          }),
          // 人工理由：无键，原样显示（后端不翻译操作人写的话）
          row({ id: 1 }),
        ],
      }),
    )
    const { wrapper } = await mountView()

    const rows = wrapper.findAll('.el-table__row')
    expect(rows[0]!.text()).toContain('棚卸調整 PD2026100101')
    expect(rows[1]!.text()).toContain('整理のため')

    i18n.global.locale.value = 'en-US'
    await flushPromises()
    const switched = wrapper.findAll('.el-table__row')
    expect(switched[0]!.text()).toContain('Stocktake adjustment PD2026100101')
    expect(switched[1]!.text()).toContain('整理のため')
    i18n.global.locale.value = 'ja-JP'
  })

  it('narrows by txn type and warehouse via the select emits', async () => {
    const { wrapper } = await mountView()

    const selects = wrapper.findAllComponents({ name: 'ElSelect' })
    await selects[0]!.vm.$emit('update:modelValue', 3)
    await selects[0]!.vm.$emit('change', 3)
    await flushPromises()
    expect(apiMocks.fetchLedgers).toHaveBeenLastCalledWith(
      expect.objectContaining({ txnType: 3, page: 1 }),
    )

    await selects[1]!.vm.$emit('update:modelValue', 2)
    await selects[1]!.vm.$emit('change', 2)
    await flushPromises()
    expect(apiMocks.fetchLedgers).toHaveBeenLastCalledWith(
      expect.objectContaining({ warehouse: 2, page: 1 }),
    )
  })

  it('assembles the half-open date window as day boundaries and paginates', async () => {
    apiMocks.fetchLedgers.mockResolvedValue(result({ total: 45 }))
    const { wrapper } = await mountView()

    const pickers = wrapper.findAllComponents({ name: 'ElDatePicker' })
    await pickers[0]!.vm.$emit('update:modelValue', '2026-09-01')
    await pickers[0]!.vm.$emit('change', '2026-09-01')
    await pickers[1]!.vm.$emit('update:modelValue', '2026-09-10')
    await pickers[1]!.vm.$emit('change', '2026-09-10')
    await flushPromises()
    // 视图层传原日；api.ts 组装 to=次日零点（api.spec 覆盖 URL 级断言）
    expect(apiMocks.fetchLedgers).toHaveBeenLastCalledWith(
      expect.objectContaining({ dateFrom: '2026-09-01', dateTo: '2026-09-10', page: 1 }),
    )

    const pagination = wrapper.findComponent({ name: 'ElPagination' })
    expect(pagination.exists()).toBe(true) // 45 > 20 才出现
    pagination.vm.$emit('current-change', 2)
    await flushPromises()
    expect(apiMocks.fetchLedgers).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 2 }),
    )
  })

  it('clears all filters back to the unfiltered first page', async () => {
    const { wrapper } = await mountView()

    const selects = wrapper.findAllComponents({ name: 'ElSelect' })
    await selects[0]!.vm.$emit('update:modelValue', 3)
    await selects[0]!.vm.$emit('change', 3)
    await flushPromises()

    await wrapper.findAll('button').find((b) => b.text() === '条件をクリア')!.trigger('click')
    await flushPromises()
    expect(apiMocks.fetchLedgers).toHaveBeenLastCalledWith(
      expect.objectContaining({ txnType: undefined, warehouse: undefined, page: 1 }),
    )
  })

  it('shows the error state and recovers via retry', async () => {
    apiMocks.fetchLedgers.mockRejectedValue(new ApiError(0, 'network down'))
    const { wrapper } = await mountView()

    expect(wrapper.find('.kcgl-error-box').text()).toContain('network down')

    apiMocks.fetchLedgers.mockResolvedValue(result({ total: 1, rows: [row()] }))
    await wrapper.find('.kcgl-error-box button').trigger('click')
    await flushPromises()
    expect(apiMocks.fetchLedgers).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.el-table__row')).toHaveLength(1)
  })

  it('navigates to the item detail on row click', async () => {
    apiMocks.fetchLedgers.mockResolvedValue(result({ rows: [row({ itemId: 42 })] }))
    const { wrapper, router } = await mountView()

    await wrapper.find('.el-table__row').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('item-detail')
    expect(router.currentRoute.value.params.id).toBe('42')
  })
})
