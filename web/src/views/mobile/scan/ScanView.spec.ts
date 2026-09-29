import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

const apiMocks = vi.hoisted(() => ({
  fetchItemByCode: vi.fn(),
  sellItem: vi.fn(),
  scrapItem: vi.fn(),
  transferItem: vi.fn(),
  returnItem: vi.fn(),
  markListedItem: vi.fn(),
  markCanceledItem: vi.fn(),
}))

// ApiError 保持真实实现（错误文案与 404 分支依赖 instanceof/code）；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchItemByCode: apiMocks.fetchItemByCode,
    sellItem: apiMocks.sellItem,
    scrapItem: apiMocks.scrapItem,
    transferItem: apiMocks.transferItem,
    returnItem: apiMocks.returnItem,
    markListedItem: apiMocks.markListedItem,
    markCanceledItem: apiMocks.markCanceledItem,
  }
})

// jsdom 无 getUserMedia：整包替换（相机流路径由真机矩阵覆盖，docs/qa/device-matrix.md）
vi.mock('vue-qrcode-reader', () => ({
  QrcodeStream: { name: 'QrcodeStream', template: '<div class="camera-stub" />' },
}))

import ScanView from './ScanView.vue'
import { i18n } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/utils/api'
import type { ItemByCode, ItemResponse, MeResponse } from '@/utils/api'

/**
 * 扫码操作页（M3-④/M5-②b）：手输定位/归一化/404 文案/作废提示（重录新号与
 * 未重录两态）/viewer 只读/按状态渲染动作菜单（边表前端镜像，含取消标记）/
 * 报废理由必填/卖出价格校验/动作幂等键失败重试复用同键（docs/01 7.0）/
 * 成功后定位卡刷新。
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

function itemByCode(
  itemOverrides: Partial<ItemResponse> = {},
  extra: Partial<ItemByCode> = {},
): ItemByCode {
  return {
    item: {
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
      fee: 300,
      shippingFee: 200,
      tax: null,
      soldPrice: null,
      totalCost: 1500,
      priceBandCode: 'X',
      warehouse: 1,
      shelfNo: null,
      warehouseInDate: null,
      groupNo: null,
      remark: '取扱注意',
      stockStatus: 1,
      saleStatus: 0,
      voided: false,
      voidReason: null,
      reEntryOf: null,
      deleted: false,
      createdAt: '2026-09-15 10:00:00',
      ...itemOverrides,
    },
    thumbUrl: '/img/thumb/2026/09/a_t.jpg',
    reEntry: null,
    ...extra,
  }
}

const actionResult = (item: ItemByCode) => ({
  itemId: item.item.id,
  itemCode: item.item.itemCode,
  stockStatus: item.item.stockStatus,
  saleStatus: item.item.saleStatus,
  warehouse: item.item.warehouse,
})

/** 内存路由（useRoute 深链 ?code= 分支需要）；query 可带预定位码。 */
async function mountView(role: 2 | 3 = 2, query: Record<string, string> = {}): Promise<VueWrapper> {
  const auth = useAuthStore()
  auth.me = role === 2 ? meEditor : meViewer
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  await router.push({ path: '/', query })
  const wrapper = mount(ScanView, {
    global: { plugins: [i18n, router] },
  })
  await flushPromises()
  return wrapper
}

/** 手输定位（IME 归一仅提交时）：全角输入也应归一为半角大写后请求。 */
async function locateByHand(wrapper: VueWrapper, raw: string): Promise<void> {
  await wrapper.find('#scan-manual-input').setValue(raw)
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

function actionButtons(wrapper: VueWrapper) {
  return wrapper.findAll('.scan-action')
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  vi.resetAllMocks()
  setActivePinia(createPinia())
  i18n.global.locale.value = 'ja-JP'
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('scan locate (M3-④)', () => {
  it('手输定位：定位卡渲染缩略图/仓库/状态/备注，管理号全角归一后请求', async () => {
    apiMocks.fetchItemByCode.mockResolvedValue(itemByCode())
    const wrapper = await mountView()

    await locateByHand(wrapper, 'ｈｔ９－ａ１ｘ')

    expect(apiMocks.fetchItemByCode).toHaveBeenCalledWith('HT9-A1X')
    expect(wrapper.find('.scan-code').text()).toBe('HT9-A1X')
    expect(wrapper.find('.scan-thumb img').attributes('src')).toBe('/img/thumb/2026/09/a_t.jpg')
    expect(wrapper.text()).toContain('名古屋倉庫')
    expect(wrapper.text()).toContain('在庫')
    expect(wrapper.text()).toContain('未出品')
    expect(wrapper.text()).toContain('取扱注意')
    expect(wrapper.find('.scan-error').exists()).toBe(false)
  })

  it('号不存在（404001）：专用文案提示', async () => {
    apiMocks.fetchItemByCode.mockRejectedValue(new ApiError(404001, 'not found'))
    const wrapper = await mountView()

    await locateByHand(wrapper, 'ZZZ9-Z9Z')

    expect(wrapper.find('.scan-error').text()).toBe('この管理番号の商品は見つかりません')
    expect(wrapper.find('.scan-card').exists()).toBe(false)
  })

  it('深链 ?code= 进页即定位该件（出荷待ち直达，全角归一）', async () => {
    apiMocks.fetchItemByCode.mockResolvedValue(itemByCode())
    const wrapper = await mountView(2, { code: 'ｈｔ９－ａ１ｘ' })

    expect(apiMocks.fetchItemByCode).toHaveBeenCalledWith('HT9-A1X')
    expect(wrapper.find('.scan-code').text()).toBe('HT9-A1X')
    expect(wrapper.find('.scan-card').exists()).toBe(true)
  })

  it('作废件带重录新号：提示新号且无动作菜单', async () => {
    apiMocks.fetchItemByCode.mockResolvedValue(
      itemByCode(
        { voided: true, voidReason: '価格ミス' },
        { reEntry: { itemId: 301, itemCode: 'HT9-A2X' } },
      ),
    )
    const wrapper = await mountView()

    await locateByHand(wrapper, 'HT9-A1X')

    expect(wrapper.text()).toContain('取り消し済み')
    expect(wrapper.text()).toContain('再登録後の管理番号は HT9-A2X です')
    expect(actionButtons(wrapper)).toHaveLength(0)
  })

  it('作废未重录：提示撕标签/划线，无动作菜单', async () => {
    apiMocks.fetchItemByCode.mockResolvedValue(itemByCode({ voided: true }))
    const wrapper = await mountView()

    await locateByHand(wrapper, 'HT9-A1X')

    expect(wrapper.text()).toContain('再登録されていません')
    expect(actionButtons(wrapper)).toHaveLength(0)
  })

  it('viewer：定位卡只读+权限提示条，无动作菜单', async () => {
    apiMocks.fetchItemByCode.mockResolvedValue(itemByCode())
    const wrapper = await mountView(3)

    await locateByHand(wrapper, 'HT9-A1X')

    expect(wrapper.text()).toContain('在庫操作には編集者以上の権限が必要です')
    expect(actionButtons(wrapper)).toHaveLength(0)
  })
})

describe('scan action menu (边表前端镜像)', () => {
  it('在库未上架：五个动作按现场频率排序', async () => {
    apiMocks.fetchItemByCode.mockResolvedValue(itemByCode())
    const wrapper = await mountView()

    await locateByHand(wrapper, 'HT9-A1X')

    const labels = actionButtons(wrapper).map((button) => button.text())
    expect(labels).toEqual(['売却', '移動', '廃棄', '出品済みにする', '会場へ返す'])
  })

  it('在库在售：取消标记出现（D-069 流拍登记口），上架标记消失', async () => {
    apiMocks.fetchItemByCode.mockResolvedValue(itemByCode({ saleStatus: 1 }))
    const wrapper = await mountView()

    await locateByHand(wrapper, 'HT9-A1X')

    expect(actionButtons(wrapper).map((b) => b.text()))
      .toEqual(['売却', '移動', '廃棄', '出品取り消し', '会場へ返す'])
  })

  it('已出库且成交：仅顾客退回', async () => {
    apiMocks.fetchItemByCode.mockResolvedValue(itemByCode({ stockStatus: 2, saleStatus: 2 }))
    const wrapper = await mountView()

    await locateByHand(wrapper, 'HT9-A1X')

    expect(actionButtons(wrapper).map((b) => b.text())).toEqual(['返品を受け取る'])
  })

  it('已出库未成交（终态）：无动作且给出说明文案', async () => {
    apiMocks.fetchItemByCode.mockResolvedValue(itemByCode({ stockStatus: 2, saleStatus: 3 }))
    const wrapper = await mountView()

    await locateByHand(wrapper, 'HT9-A1X')

    expect(actionButtons(wrapper)).toHaveLength(0)
    expect(wrapper.find('.scan-no-actions').text()).toBe('現在の状態では操作できません')
  })
})

describe('scan action dialogs (幂等键契约 docs/01 7.0)', () => {
  it('报废：理由必填（空理由不出请求）；成功后弹层关闭+横幅+定位卡刷新', async () => {
    const before = itemByCode()
    const after = itemByCode({ stockStatus: 2, saleStatus: 3 })
    apiMocks.fetchItemByCode
      .mockResolvedValueOnce(before)
      .mockResolvedValueOnce(after)
    apiMocks.scrapItem.mockResolvedValue(actionResult(after))
    const wrapper = await mountView()

    await locateByHand(wrapper, 'HT9-A1X')
    await actionButtons(wrapper)[2].trigger('click') // 廃棄
    await flushPromises()

    await wrapper.find('.scan-dialog-ok').trigger('click')
    await flushPromises()
    expect(apiMocks.scrapItem).not.toHaveBeenCalled()
    expect(wrapper.find('.scan-dialog-error').text()).toBe('廃棄理由を入力してください')

    await wrapper.find('#scan-scrap-reason').setValue('破損のため')
    await wrapper.find('.scan-dialog-ok').trigger('click')
    await flushPromises()

    expect(apiMocks.scrapItem).toHaveBeenCalledWith(201, expect.any(String), '破損のため')
    expect(wrapper.find('.scan-overlay').exists()).toBe(false)
    expect(wrapper.find('.scan-done').text()).toBe('廃棄を記録しました')
    // 卡片按动作后现态刷新（出庫済み+キャンセル）→ 动作菜单随新态收缩
    expect(wrapper.text()).toContain('出庫済み')
    expect(wrapper.text()).toContain('キャンセル')
    expect(actionButtons(wrapper)).toHaveLength(0)
    expect(apiMocks.fetchItemByCode).toHaveBeenCalledTimes(2)
  })

  it('报废失败重试：同一幂等键复用（服务端读回原结果出清的前提）', async () => {
    const target = itemByCode()
    apiMocks.fetchItemByCode.mockResolvedValue(target)
    apiMocks.scrapItem
      .mockRejectedValueOnce(new ApiError(500000, 'boom'))
      .mockResolvedValueOnce(actionResult(itemByCode({ stockStatus: 2, saleStatus: 3 })))
    const wrapper = await mountView()

    await locateByHand(wrapper, 'HT9-A1X')
    await actionButtons(wrapper)[2].trigger('click')
    await wrapper.find('#scan-scrap-reason').setValue('破損のため')

    await wrapper.find('.scan-dialog-ok').trigger('click')
    await flushPromises()
    expect(wrapper.find('.scan-overlay').exists()).toBe(true)
    expect(wrapper.find('.scan-dialog-error').exists()).toBe(true)

    await wrapper.find('.scan-dialog-ok').trigger('click')
    await flushPromises()

    const firstKey = apiMocks.scrapItem.mock.calls[0][1]
    const secondKey = apiMocks.scrapItem.mock.calls[1][1]
    expect(secondKey).toBe(firstKey)
    expect(wrapper.find('.scan-overlay').exists()).toBe(false)
  })

  it('卖出：价格非法不出请求；合法价格随请求提交', async () => {
    const target = itemByCode()
    apiMocks.fetchItemByCode.mockResolvedValue(target)
    apiMocks.sellItem.mockResolvedValue(actionResult(itemByCode({ stockStatus: 2, saleStatus: 2 })))
    const wrapper = await mountView()

    await locateByHand(wrapper, 'HT9-A1X')
    await actionButtons(wrapper)[0].trigger('click') // 売却

    await wrapper.find('#scan-sold-price').setValue('0')
    await wrapper.find('.scan-dialog-ok').trigger('click')
    await flushPromises()
    expect(apiMocks.sellItem).not.toHaveBeenCalled()
    expect(wrapper.find('.scan-dialog-error').exists()).toBe(true)

    await wrapper.find('#scan-sold-price').setValue('１５,０００')
    await wrapper.find('.scan-dialog-ok').trigger('click')
    await flushPromises()

    expect(apiMocks.sellItem).toHaveBeenCalledWith(201, expect.any(String), 15000)
  })

  it('卖出：价格留空合法（雅虎受注回填场景）', async () => {
    apiMocks.fetchItemByCode.mockResolvedValue(itemByCode())
    apiMocks.sellItem.mockResolvedValue(actionResult(itemByCode({ stockStatus: 2, saleStatus: 2 })))
    const wrapper = await mountView()

    await locateByHand(wrapper, 'HT9-A1X')
    await actionButtons(wrapper)[0].trigger('click')
    await wrapper.find('.scan-dialog-ok').trigger('click')
    await flushPromises()

    expect(apiMocks.sellItem).toHaveBeenCalledWith(201, expect.any(String), undefined)
  })

  it('调拨：默认对侧仓库，可切换；同键请求带目标仓', async () => {
    apiMocks.fetchItemByCode.mockResolvedValue(itemByCode({ warehouse: 1 }))
    apiMocks.transferItem.mockResolvedValue(actionResult(itemByCode({ warehouse: 2 })))
    const wrapper = await mountView()

    await locateByHand(wrapper, 'HT9-A1X')
    await actionButtons(wrapper)[1].trigger('click') // 移動（默认 2=福岡）
    await flushPromises()

    const options = wrapper.findAll('.scan-wh-option')
    expect(options[1].classes()).toContain('is-active')

    // jsdom 不转发 label 点击到内嵌 input：直接驱动 radio（真实浏览器点 label 即可）
    const radios = wrapper.findAll('input[name="scan-transfer-to"]')
    await radios[0].setValue(true) // 切回名古屋
    await wrapper.find('.scan-dialog-ok').trigger('click')
    await flushPromises()

    expect(apiMocks.transferItem).toHaveBeenCalledWith(201, expect.any(String), 1)
    // 刷新后的卡片显示新仓库
    expect(apiMocks.fetchItemByCode).toHaveBeenCalledTimes(2)
  })

  it('上架标记：无参数确认即请求', async () => {
    apiMocks.fetchItemByCode.mockResolvedValue(itemByCode())
    apiMocks.markListedItem.mockResolvedValue(actionResult(itemByCode({ saleStatus: 1 })))
    const wrapper = await mountView()

    await locateByHand(wrapper, 'HT9-A1X')
    await actionButtons(wrapper)[3].trigger('click') // 出品済みにする
    await flushPromises()

    // 锚定 dialogKey 归并（回归：直拼动作名曾渲染原始键名 scan.markListed.title）
    expect(wrapper.find('.scan-dialog-title').text()).toBe('出品済みにする')
    expect(wrapper.find('.scan-dialog-ok').text()).toBe('出品中として記録する')
    expect(wrapper.text()).toContain('ヤフオク!')
    await wrapper.find('.scan-dialog-ok').trigger('click')
    await flushPromises()

    expect(apiMocks.markListedItem).toHaveBeenCalledWith(201, expect.any(String))
  })

  it('取消标记：在售态登记流拍/出品取消；成功后横幅+卡片刷新', async () => {
    apiMocks.fetchItemByCode
      .mockResolvedValueOnce(itemByCode({ saleStatus: 1 }))
      .mockResolvedValueOnce(itemByCode({ saleStatus: 3 }))
    apiMocks.markCanceledItem.mockResolvedValue(actionResult(itemByCode({ saleStatus: 3 })))
    const wrapper = await mountView()

    await locateByHand(wrapper, 'HT9-A1X')
    await actionButtons(wrapper)[3].trigger('click') // 出品取り消し
    await flushPromises()

    expect(wrapper.find('.scan-dialog-title').text()).toBe('出品取り消し')
    // 受注文件无取消信息 → 手动登记是唯一来源（弹层提示明示）
    expect(wrapper.text()).toContain('キャンセル情報')
    await wrapper.find('.scan-dialog-ok').trigger('click')
    await flushPromises()

    expect(apiMocks.markCanceledItem).toHaveBeenCalledWith(201, expect.any(String))
    expect(wrapper.find('.scan-done').text()).toBe('出品取り消しを記録しました')
    expect(apiMocks.fetchItemByCode).toHaveBeenCalledTimes(2)
    // 刷新后的卡片销售态=キャンセル
    expect(wrapper.text()).toContain('キャンセル')
  })

  it('顾客退回：说明随请求（direction=1）；说明留空不提交空串', async () => {
    apiMocks.fetchItemByCode.mockResolvedValue(itemByCode({ stockStatus: 2, saleStatus: 2 }))
    apiMocks.returnItem.mockResolvedValue(actionResult(itemByCode({ stockStatus: 1, saleStatus: 3 })))
    const wrapper = await mountView()

    await locateByHand(wrapper, 'HT9-A1X')
    await actionButtons(wrapper)[0].trigger('click') // 返品を受け取る
    await wrapper.find('#scan-return-note').setValue('お客様都合')
    await wrapper.find('.scan-dialog-ok').trigger('click')
    await flushPromises()

    expect(apiMocks.returnItem).toHaveBeenCalledWith(201, expect.any(String), 1, 'お客様都合')
  })

  it('会场退回：direction=2，说明留空不传', async () => {
    apiMocks.fetchItemByCode.mockResolvedValue(itemByCode())
    apiMocks.returnItem.mockResolvedValue(actionResult(itemByCode({ stockStatus: 2, saleStatus: 3 })))
    const wrapper = await mountView()

    await locateByHand(wrapper, 'HT9-A1X')
    await actionButtons(wrapper)[4].trigger('click') // 会場へ返す
    await wrapper.find('.scan-dialog-ok').trigger('click')
    await flushPromises()

    expect(apiMocks.returnItem).toHaveBeenCalledWith(201, expect.any(String), 2)
  })

  it('操作中：取消与重复提交被拦截（busy 守卫）', async () => {
    let resolveAction: (value: ReturnType<typeof actionResult>) => void = () => {}
    apiMocks.fetchItemByCode.mockResolvedValue(itemByCode())
    apiMocks.markListedItem.mockImplementation(
      () => new Promise((resolve) => { resolveAction = resolve }),
    )
    const wrapper = await mountView()

    await locateByHand(wrapper, 'HT9-A1X')
    await actionButtons(wrapper)[3].trigger('click')
    await wrapper.find('.scan-dialog-ok').trigger('click')
    await flushPromises()

    const cancel = wrapper.find('.scan-dialog-cancel')
    expect(cancel.attributes('disabled')).toBeDefined()
    await wrapper.find('.scan-dialog-ok').trigger('click')
    expect(apiMocks.markListedItem).toHaveBeenCalledTimes(1)

    resolveAction(actionResult(itemByCode({ saleStatus: 1 })))
    await flushPromises()
    expect(wrapper.find('.scan-overlay').exists()).toBe(false)
  })
})
