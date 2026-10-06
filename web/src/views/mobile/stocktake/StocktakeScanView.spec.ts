import 'fake-indexeddb/auto'
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
const scanQueue = useScanQueue()

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
import { useScanQueue } from '@/composables/useScanQueue'
import { ApiError } from '@/utils/api'
import type { ItemResponse, MeResponse, StocktakeSummary } from '@/utils/api'

/**
 * 盘点会话页（M3-⑥）：进行中=相机+手动兜底扫码记录（repeated 不报错不加数/
 * 他仓/冻结/非在库照记卡内警示——docs/01 7.3）/close 成功跳差异页/cancel 仅
 * 发起人/已 close 只读引导差异/viewer 只读/404 文案。
 * M6-②：网络失败照记本地小队列（离线卡+计数含队列+close 拦截），恢复 online
 * 自动回放（幂等 200 出清）→ 计数切回服务端口径；回放业务拒绝落警示可关。
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
    mine: true,
    ...overrides,
  }
}

function item(overrides: Partial<ItemResponse> = {}): ItemResponse {
  return {
    id: 201,
    itemCode: 'HT9-A1X',
    venueId: 1,
    venueCode: 'HT',
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

beforeEach(async () => {
  vi.resetAllMocks()
  pushMock.mockReset()
  setActivePinia(createPinia())
  i18n.global.locale.value = 'ja-JP'
  await scanQueue.resetForTests()
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

    await wrapper.find('#stocktake-manual-input').setValue(' ht9-a1x ')
    await wrapper.find('.session-manual').trigger('submit')
    await flushPromises()

    // 手动输入提交前归一化（NFKC+大写+去空白）；乐观计数 3→4
    expect(apiMocks.scanStocktakeItem).toHaveBeenCalledWith(5, 'HT9-A1X')
    expect(wrapper.text()).toContain('スキャン済み 4 件')
    expect(wrapper.text()).toContain('HT9-A1X')
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

    await wrapper.find('#stocktake-manual-input').setValue('HT9-A1X')
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

      await wrapper.find('#stocktake-manual-input').setValue('HT9-A1X')
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

describe('stocktake offline queue (M6-2)', () => {
  it('queues the scan locally on network failure: offline card, count includes queue, close blocked', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(summary())
    apiMocks.scanStocktakeItem.mockRejectedValue(new ApiError(0, 'NETWORK_ERROR'))
    const wrapper = await mountView()

    await wrapper.find('#stocktake-manual-input').setValue('HT9-A1X')
    await wrapper.find('.session-manual').trigger('submit')

    // 离线卡：码 + 待送信提示；详情/缩略图离线拿不到（只显码）
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('オフラインで記録しました')
    })
    expect(wrapper.text()).toContain('HT9-A1X')
    expect(wrapper.find('.session-card img').exists()).toBe(false)
    // 计数口径=服务端 3 + 队列 1
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('スキャン済み 4 件')
    })
    // close 拦截：未送信存续期间不可冻结期望集合
    expect(wrapper.find('.session-actions .kcgl-btn-primary').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('未送信のスキャンが 1 件あります')
    // 报错条不走网络错误文案（照记成功，非失败）
    expect(wrapper.find('.session-error').exists()).toBe(false)
  })

  it('replays the queued scan when back online, switches the count to the server side, and re-enables close', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(summary())
    apiMocks.scanStocktakeItem
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR'))
      .mockResolvedValueOnce({ repeated: false, item: item(), thumbUrl: null })
    const wrapper = await mountView()

    await wrapper.find('#stocktake-manual-input').setValue('HT9-A1X')
    await wrapper.find('.session-manual').trigger('submit')
    await vi.waitFor(() => {
      expect(wrapper.find('.session-actions .kcgl-btn-primary').attributes('disabled')).toBeDefined()
    })

    // 恢复网络（online 事件）：回放幂等成功 → 队列清空 → 重取服务端计数
    apiMocks.fetchStocktake.mockResolvedValue(summary({ scannedCount: 4 }))
    window.dispatchEvent(new Event('online'))
    await vi.waitFor(() => {
      expect(apiMocks.scanStocktakeItem).toHaveBeenCalledTimes(2) // 离线失败 1 + 回放 1
    })
    await vi.waitFor(() => {
      expect(wrapper.find('.session-actions .kcgl-btn-primary').attributes('disabled')).toBeUndefined()
    })
    expect(wrapper.text()).toContain('スキャン済み 4 件')
    expect(wrapper.text()).not.toContain('未送信のスキャンが')
    expect(apiMocks.fetchStocktake).toHaveBeenCalledTimes(2) // 回放清空触发口径刷新
  })

  it('surfaces a business rejection from the replay and keeps the session closeable', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(summary())
    apiMocks.scanStocktakeItem
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR')) // 离线入队
      .mockRejectedValueOnce(new ApiError(404001, 'NOT_FOUND')) // 回放被拒（码不存在）
    const wrapper = await mountView()

    await wrapper.find('#stocktake-manual-input').setValue('HT9-XXX')
    await wrapper.find('.session-manual').trigger('submit')
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('オフラインで記録しました')
    })

    window.dispatchEvent(new Event('online'))
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('送信できなかったスキャン')
    })
    expect(wrapper.text()).toContain('HT9-XXX')
    // 业务拒绝即出清（不无限重试）：close 不再被拦，计数回落服务端口径
    await vi.waitFor(() => {
      expect(wrapper.find('.session-actions .kcgl-btn-primary').attributes('disabled')).toBeUndefined()
    })
    expect(wrapper.text()).toContain('スキャン済み 3 件')

    // 「閉じる」消除警示
    await wrapper.find('.session-flush-dismiss').trigger('click')
    expect(wrapper.text()).not.toContain('送信できなかったスキャン')
  })
})

/**
 * 扫码闸门与计数重取的时序（审计项 C2/C3）。
 *
 * C2：排空触发的重取与扫码在途**互斥**——在途直取会让随后的乐观写入按旧基数
 * 覆盖回取结果（回卷），静默丢弃又会让本次回放的口径切换永久缺席；故挂起 + 结算后补取。
 * C3：失败扫码（卡片未出现）须解锁同码重扫；成功扫码**不得**解锁（同码短窗重扫=扫码枪回声）。
 */
describe('stocktake scan gate and refresh sequencing (C2/C3)', () => {
  it('suspends the drain-triggered refresh while a scan is in flight, then settles it after the response', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(summary())
    const wrapper = await mountView()

    // 入队必须在挂载**之后**：init() 见队列非空会自己冲一次（fire-and-forget），
    // 那次回放会抢在下面装的在途桩之前消耗 mockImplementationOnce，时序就不再受控
    await scanQueue.enqueue(5, 'HT9-A2Y')
    await flushPromises()
    expect(wrapper.text()).toContain('スキャン済み 4 件')

    // 手工输入持有在途请求（不经扫码闸门：本项只关乎重取时序）；后续回放那次照常放行
    let releaseScan: ((value: unknown) => void) | undefined
    apiMocks.scanStocktakeItem
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            releaseScan = resolve
          }),
      )
      .mockResolvedValue({ repeated: false, item: item(), thumbUrl: null })

    await wrapper.find('#stocktake-manual-input').setValue('HT9-A1X')
    void wrapper.find('.session-manual').trigger('submit')
    await vi.waitFor(() => {
      expect(apiMocks.scanStocktakeItem).toHaveBeenCalledTimes(1)
    })

    // 排空恰好落在扫码在途窗口内
    apiMocks.fetchStocktake.mockResolvedValue(summary({ scannedCount: 9 }))
    await scanQueue.flush()
    await flushPromises()

    // 在途期间不得直取（直取结果会被随后到达的乐观写入按旧基数覆盖 → 回卷）
    expect(apiMocks.fetchStocktake).toHaveBeenCalledTimes(1)

    // 结算后补取一次：口径切到服务端（否则本次回放永久少记）
    expect(releaseScan).toBeDefined()
    releaseScan?.({ repeated: false, item: item(), thumbUrl: null })
    await vi.waitFor(() => {
      expect(apiMocks.fetchStocktake).toHaveBeenCalledTimes(2)
    })
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('スキャン済み 9 件')
    })
  })

  it('releases the same-code gate when a scan fails: the same code can be rescanned at once', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(summary())
    apiMocks.scanStocktakeItem.mockRejectedValue(new ApiError(404001, 'NOT_FOUND'))
    const wrapper = await mountView()
    const camera = wrapper.findComponent({ name: 'QrcodeStream' })

    camera.vm.$emit('detect', [{ rawValue: 'HT9-XXX' }])
    await vi.waitFor(() => {
      expect(wrapper.find('.session-error').exists()).toBe(true)
    })

    // 同码即时重扫（间隔远小于 3s 同码窗）必须真打端点——否则操作员听不到提示音、
    // 报错也不刷新，只会以为扫码枪失效
    camera.vm.$emit('detect', [{ rawValue: 'HT9-XXX' }])
    await vi.waitFor(() => {
      expect(apiMocks.scanStocktakeItem).toHaveBeenCalledTimes(2)
    })
  })

  it('keeps suppressing the same-code echo while scans succeed', async () => {
    apiMocks.fetchStocktake.mockResolvedValue(summary())
    apiMocks.scanStocktakeItem.mockResolvedValue({
      repeated: false,
      item: item(),
      thumbUrl: null,
    })
    const wrapper = await mountView()
    const camera = wrapper.findComponent({ name: 'QrcodeStream' })

    camera.vm.$emit('detect', [{ rawValue: 'HT9-A1X' }])
    await vi.waitFor(() => {
      expect(wrapper.find('.session-card').exists()).toBe(true)
    })

    // 成功路径不解锁：同码短窗重扫是扫码枪回声，仍须抑制
    camera.vm.$emit('detect', [{ rawValue: 'HT9-A1X' }])
    await flushPromises()
    expect(apiMocks.scanStocktakeItem).toHaveBeenCalledTimes(1)
  })
})
