import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  fetchDashboardStats: vi.fn(),
  fetchWarehouseStats: vi.fn(),
  fetchChecklist: vi.fn(),
  markChecklistPrintDone: vi.fn(),
}))

// ApiError/errors/format 保持真实实现；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchDashboardStats: apiMocks.fetchDashboardStats,
    fetchWarehouseStats: apiMocks.fetchWarehouseStats,
    fetchChecklist: apiMocks.fetchChecklist,
    markChecklistPrintDone: apiMocks.markChecklistPrintDone,
  }
})

const pushMock = vi.hoisted(() => vi.fn())

// 视图内跳转（出荷待ち/ヤフー連携）用 useRouter 桩
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRouter: () => ({ push: pushMock }),
  }
})

import DashboardView from './DashboardView.vue'
import { i18n } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import { ApiError, type DashboardStats, type MeResponse, type WarehouseStats } from '@/utils/api'

/**
 * 桌面大盘（M5-③）：全队指标卡 + 雅虎同步卡（含最近导入新鲜度）+ 两仓明细表；
 * 空库龄/无批次占位「—」；加载失败可重试。首启引导卡见 SetupChecklistCard.spec。
 */

const meAdmin: MeResponse = {
  id: 1,
  username: 'boss',
  displayName: '管理者',
  role: 1,
  locale: 'ja-JP',
  mustChangePwd: false,
}

function fleet(overrides: Partial<WarehouseStats> = {}): WarehouseStats {
  return {
    warehouse: null,
    totalItems: 14,
    inStock: 8,
    inTransit: 1,
    shipped: 5,
    monthInbound: 2,
    monthOutbound: 3,
    slowWarn: 1,
    slowRed: 2,
    stockValue: 11500,
    avgStockAgeDays: 12.2,
    ...overrides,
  }
}

function dashboard(overrides: Partial<DashboardStats> = {}): DashboardStats {
  return {
    fleet: fleet(),
    yahoo: {
      soldNotShipped: 4,
      canceledNotRelisted: 1,
      withdrawNeeded: 2,
      lastImportFinishedAt: '2026-09-01T10:15:30',
      lastImportFilename: 'orders-latest.csv',
    },
    ...overrides,
  }
}

function warehouses(): WarehouseStats[] {
  return [
    { ...fleet({ warehouse: 1, inStock: 4, stockValue: 6500, avgStockAgeDays: 2 }) },
    { ...fleet({ warehouse: 2, inStock: 4, stockValue: 5000, avgStockAgeDays: 27.5 }) },
  ]
}

async function mountView(): Promise<VueWrapper> {
  const auth = useAuthStore()
  auth.me = meAdmin
  const wrapper = mount(DashboardView, {
    global: { plugins: [i18n] },
  })
  await flushPromises()
  return wrapper
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  vi.resetAllMocks()
  setActivePinia(createPinia())
  i18n.global.locale.value = 'ja-JP'
  apiMocks.fetchChecklist.mockResolvedValue({
    hasStaffUser: true,
    hasVenue: true,
    hasPriceBand: true,
    hasItem: true,
    printDone: true,
  })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('dashboard rendering', () => {
  it('renders the fleet stat grid with counts, yen value, and stock age', async () => {
    apiMocks.fetchDashboardStats.mockResolvedValue(dashboard())
    apiMocks.fetchWarehouseStats.mockResolvedValue(warehouses())
    const wrapper = await mountView()

    expect(apiMocks.fetchDashboardStats).toHaveBeenCalledTimes(1)
    expect(apiMocks.fetchWarehouseStats).toHaveBeenCalledTimes(1)
    const stat = (key: string): string =>
      wrapper.find(`.dashboard-stat.is-${key} dd`).text()
    expect(stat('inStock')).toBe('8')
    expect(stat('inTransit')).toBe('1')
    expect(stat('shipped')).toBe('5')
    expect(stat('monthInbound')).toBe('2')
    expect(stat('monthOutbound')).toBe('3')
    expect(stat('slowWarn')).toBe('1')
    expect(stat('slowRed')).toBe('2')
    expect(stat('stockValue')).toContain('11,500')
    expect(stat('avgStockAgeDays')).toBe('12.2日')
  })

  it('renders the yahoo card counts, last import freshness, and navigates on link buttons', async () => {
    apiMocks.fetchDashboardStats.mockResolvedValue(dashboard())
    apiMocks.fetchWarehouseStats.mockResolvedValue(warehouses())
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('落札済み・未出庫')
    // is-* 类名与 fleet 同口径（E2E 靠它定位 yahoo 三统计——漏绑会静默漏类名）
    const yahooStat = (key: string): string =>
      wrapper.find(`.dashboard-columns .dashboard-stat.is-${key} dd`).text()
    expect(yahooStat('soldNotShipped')).toBe('4')
    expect(yahooStat('canceledNotRelisted')).toBe('1')
    expect(yahooStat('withdrawNeeded')).toBe('2')
    // 最近导入：文件名 + JST 日期时间（秒不展示）
    expect(wrapper.find('.dashboard-last-import').text())
      .toBe('最終インポート：orders-latest.csv（2026/09/01 10:15）')

    await wrapper.findAll('.dashboard-link-btn')[0]!.trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ name: 'pending-shipments' })
    await wrapper.findAll('.dashboard-link-btn')[1]!.trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ name: 'yahoo' })
  })

  it('shows the no-import placeholder when no batch has ever succeeded', async () => {
    apiMocks.fetchDashboardStats.mockResolvedValue(
      dashboard({
        yahoo: {
          soldNotShipped: 0,
          canceledNotRelisted: 0,
          withdrawNeeded: 0,
          lastImportFinishedAt: null,
          lastImportFilename: null,
        },
      }),
    )
    apiMocks.fetchWarehouseStats.mockResolvedValue(warehouses())
    const wrapper = await mountView()

    expect(wrapper.find('.dashboard-last-import').text())
      .toBe('受注ファイルのインポートはまだありません')
  })

  it('renders both warehouses with their own metrics in the table', async () => {
    apiMocks.fetchDashboardStats.mockResolvedValue(dashboard())
    apiMocks.fetchWarehouseStats.mockResolvedValue(warehouses())
    const wrapper = await mountView()

    const rows = wrapper.findAll('.dashboard-table tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.find('th').text()).toBe('名古屋倉庫')
    expect(rows[1]!.find('th').text()).toBe('福岡倉庫')
    expect(rows[0]!.findAll('td')[8]!.text()).toBe('2日')
    expect(rows[1]!.findAll('td')[8]!.text()).toBe('27.5日')
    expect(rows[0]!.findAll('td')[7]!.text()).toContain('6,500')
  })

  it('renders the placeholder for a null average stock age (no in-stock items)', async () => {
    apiMocks.fetchDashboardStats.mockResolvedValue(
      dashboard({ fleet: fleet({ avgStockAgeDays: null }) }),
    )
    apiMocks.fetchWarehouseStats.mockResolvedValue(warehouses())
    const wrapper = await mountView()

    expect(wrapper.find('.dashboard-stat.is-avgStockAgeDays dd').text()).toBe('—')
  })
})

describe('dashboard loading states', () => {
  it('shows the error state on load failure, then recovers via retry', async () => {
    apiMocks.fetchDashboardStats.mockRejectedValue(
      new ApiError(500000, 'システムエラーが発生しました', 'err-0500'),
    )
    apiMocks.fetchWarehouseStats.mockResolvedValue(warehouses())
    const wrapper = await mountView()

    expect(wrapper.find('.dashboard-error-message').text()).toContain('システムエラーが発生しました')
    expect(wrapper.find('.dashboard-grid').exists()).toBe(false)

    apiMocks.fetchDashboardStats.mockResolvedValue(dashboard())
    await wrapper.find('.dashboard-retry').trigger('click')
    await flushPromises()

    expect(apiMocks.fetchDashboardStats).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.dashboard-stat.is-inStock dd').text()).toBe('8')
  })
})
