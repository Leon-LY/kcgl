import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  fetchStocktake: vi.fn(),
  scanStocktakeItem: vi.fn(),
  closeStocktake: vi.fn(),
  cancelStocktake: vi.fn(),
}))

// ApiError 保持真实实现（404 分支依赖 instanceof/code）；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchStocktake: apiMocks.fetchStocktake,
    scanStocktakeItem: apiMocks.scanStocktakeItem,
    closeStocktake: apiMocks.closeStocktake,
    cancelStocktake: apiMocks.cancelStocktake,
  }
})

// jsdom 无 getUserMedia：整包替换（相机流路径由真机矩阵覆盖，docs/qa/device-matrix.md）
vi.mock('vue-qrcode-reader', () => ({
  QrcodeStream: { name: 'QrcodeStream', template: '<div class="camera-stub" />' },
}))

const pushMock = vi.hoisted(() => vi.fn())
const routeMock = vi.hoisted(() => ({ params: { id: '5' } }))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => routeMock,
    useRouter: () => ({ push: pushMock }),
  }
})

import StocktakeScanView from './StocktakeScanView.vue'
import { i18n } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/utils/api'
import type { ItemResponse, MeResponse, StocktakeSummary } from '@/utils/api'

/**
 * 盘点会话页（M3-⑥）：进行中=相机+手动兜底扫码记录（repeated 不报错不加数/
 * 他仓/冻结/非在库照记卡内警示——docs/01 7.3）/close 成功跳差异页/cancel 仅
 * 发起人/已 close 只读引导差异/viewer 只读/404 文案。
 */

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
    status: 0,
    expectedCount: null,
    scannedCount: 3,
    diffCount: null,
    pendingDiffCount: null,
    createdAt: '2026-09-28 09:00:00',
    closedAt: null,
    createdByName: '編集者',
    closedByName: null,
    mine: true,
    ...overrides,
  }
}

function item(overrides: Partial<ItemResponse> = {}): ItemResponse {
  return {
    id: 201,
    itemCode: 'HTK9-A1X',
    venueId: 1,
    venueCode: 'HT',
    year: 2026,
    yearCode: 'K',
    buyMonth: 9,
    seqPrefix: 'A',
    seqNo: 1,
    buyDate: '2026-09-15',
    photoDate: null,
    purchasePrice: 1000,
    fee: null,
    shippingFee: null,
    tax: null,
    soldPrice: null,
    totalCost: 1000,
    priceBandCode: 'X',
    warehouse: 1,
    shelfNo: null,
    warehouseInDate: null,
    groupNo: null,
    remark: null,
    stockStatus: 1,
    saleStatus: 0,
    voided: false,
    voidReason: null,
    reEntryOf: null,
    deleted: false,
    createdAt: '2026-09-15 10:00:00',
    ...overrides,
  }
}

async function mountView(role: 2 | 3 = 2): Promise<VueWrapper> {
  const auth = useAuthStore()
  auth.me = role === 2 ? meEditor : meViewer
  const wrapper = mount(StocktakeScanView, {
    global: { plugins: [i18n] },
  })
  await flushPromises()
  return wrapper
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  vi.resetAllMocks()
  pushMock.mockReset()
  setActivePinia(createPinia())
  i18n.global.locale.value = 'ja-JP'
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('stocktake session (M3-6)', () => {
  it('mounts an active session with camera, guide, and scanned count', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(summary())
    const wrapper = await mountView()

    expect(apiMocks.fetchStocktake).toHaveBeenCalledWith(5)
    expect(wrapper.text()).toContain('PD20260928-01')
    expect(wrapper.text()).toContain('実行中')
    expect(wrapper.text()).toContain('スキャン済み 3 件')
    expect(wrapper.text()).toContain('商品の管理番号QRコードを順にかざしてください')
    expect(wrapper.find('.session-camera').exists()).toBe(true)
    expect(wrapper.find('.session-manual').exists()).toBe(true)
  })

  it('records a scanned code via manual input and increments the count', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(summary())
    apiMocks.scanStocktakeItem.mockResolvedValue({
      repeated: false,
      item: item(),
      thumbUrl: '/img/thumb/2026/09/a.jpg',
    })
    const wrapper = await mountView()

    await wrapper.find('#stocktake-manual-input').setValue(' htk9-a1x ')
    await wrapper.find('.session-manual').trigger('submit')
    await flushPromises()

    // 手动输入提交前归一化（NFKC+大写+去空白）；乐观计数 3→4
    expect(apiMocks.scanStocktakeItem).toHaveBeenCalledWith(5, 'HTK9-A1X')
    expect(wrapper.text()).toContain('スキャン済み 4 件')
    expect(wrapper.text()).toContain('HTK9-A1X')
    expect(wrapper.find('.session-card img').attributes('src')).toBe('/img/thumb/2026/09/a.jpg')
  })

  it('shows the repeated note without incrementing the count', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(summary())
    apiMocks.scanStocktakeItem.mockResolvedValue({
      repeated: true,
      item: item(),
      thumbUrl: null,
    })
    const wrapper = await mountView()

    await wrapper.find('#stocktake-manual-input').setValue('HTK9-A1X')
    await wrapper.find('.session-manual').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('スキャン済みの商品です')
    expect(wrapper.text()).toContain('スキャン済み 3 件')
  })

  it.each([
    ['warnOtherWarehouse', { warehouse: 2 }, '他倉庫の商品です'],
    ['warnNotInStock', { stockStatus: 2 }, 'システム上は在庫以外の状態です'],
    ['warnFrozen', { voided: true }, '取り消し済み・削除済みの商品です'],
  ] as const)(
    'records the item and derives the %s warning in the card',
    async (_key, itemOverrides, expectedText) => {
      apiMocks.fetchStocktake.mockResolvedValue(summary())
      apiMocks.scanStocktakeItem.mockResolvedValue({
        repeated: false,
        item: item(itemOverrides),
        thumbUrl: null,
      })
      const wrapper = await mountView()

      await wrapper.find('#stocktake-manual-input').setValue('HTK9-A1X')
      await wrapper.find('.session-manual').trigger('submit')
      await flushPromises()

      // 照记不拦（docs/01 7.3）：计数照加，卡内警示提示差异阶段裁决
      expect(apiMocks.scanStocktakeItem).toHaveBeenCalledTimes(1)
      expect(wrapper.text()).toContain('スキャン済み 4 件')
      expect(wrapper.find('.session-card-note.is-warning').text()).toContain(expectedText)
    },
  )

  it('closes the session and navigates to the difference review', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(summary({ mine: false }))
    apiMocks.closeStocktake.mockResolvedValue(summary({ status: 1, expectedCount: 8, scannedCount: 8, diffCount: 1, pendingDiffCount: 1 }))
    const wrapper = await mountView()

    await wrapper.find('.session-actions .kcgl-btn-primary').trigger('click')
    expect(wrapper.find('.session-dialog').exists()).toBe(true)
    expect(wrapper.text()).toContain('締めると差異一覧が作成されます')

    await wrapper.find('.session-dialog-actions .kcgl-btn-primary').trigger('click')
    await flushPromises()

    expect(apiMocks.closeStocktake).toHaveBeenCalledWith(5)
    expect(pushMock).toHaveBeenCalledWith({ name: 'stocktake-diffs', params: { id: 5 } })
  })

  it('cancels the session on the confirm dialog when initiated by the current user', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(summary({ mine: true }))
    apiMocks.cancelStocktake.mockResolvedValue(summary({ status: 3 }))
    const wrapper = await mountView()

    await wrapper.find('.session-cancel-btn').trigger('click')
    expect(wrapper.text()).toContain('この棚卸を取り消します')

    await wrapper.find('.session-dialog-actions .kcgl-btn-primary').trigger('click')
    await flushPromises()

    expect(apiMocks.cancelStocktake).toHaveBeenCalledWith(5)
    expect(pushMock).toHaveBeenCalledWith({ name: 'stocktake' })
  })

  it('renders a closed session as read-only with a link to the differences', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(
      summary({ status: 1, expectedCount: 10, scannedCount: 8, diffCount: 2, pendingDiffCount: 2, closedAt: '2026-09-28 12:00:00', mine: true }),
    )
    const wrapper = await mountView()

    expect(wrapper.find('.session-camera').exists()).toBe(false)
    expect(wrapper.find('.session-actions').exists()).toBe(false)
    expect(wrapper.text()).toContain('この棚卸は締められています')
    expect(wrapper.text()).toContain('予定 10 件・スキャン 8 件')

    await wrapper.find('.kcgl-btn-primary').trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ name: 'stocktake-diffs', params: { id: 5 } })
  })

  it('renders a read-only view for viewers without camera or actions', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(summary())
    const wrapper = await mountView(3)

    expect(wrapper.find('.session-camera').exists()).toBe(false)
    expect(wrapper.find('.session-manual').exists()).toBe(false)
    expect(wrapper.find('.session-actions').exists()).toBe(false)
    expect(wrapper.text()).toContain('棚卸の開始と操作には編集者以上の権限が必要です')
  })

  it('shows the not-found hint when the stocktake does not exist', async () => {
    apiMocks.fetchStocktake.mockRejectedValue(new ApiError(404001, 'NOT_FOUND'))
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('この棚卸は見つかりません')
    expect(wrapper.find('.session-summary').exists()).toBe(false)
  })
})
