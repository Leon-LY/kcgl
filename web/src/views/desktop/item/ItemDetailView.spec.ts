import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, createWebHistory, type Router } from 'vue-router'

const apiMocks = vi.hoisted(() => ({
  fetchItem: vi.fn(),
  fetchItemImages: vi.fn(),
  fetchItemLedgers: vi.fn(),
  fetchItemYahooListings: vi.fn(),
  fetchVenues: vi.fn(),
  updateItem: vi.fn(),
  voidItem: vi.fn(),
  deleteItem: vi.fn(),
  deleteItemImage: vi.fn(),
  reorderItemImages: vi.fn(),
  adjustItem: vi.fn(),
}))

// ApiError 保持真实实现（404001/409000 分支依赖 instanceof+code）；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchItem: apiMocks.fetchItem,
    fetchItemImages: apiMocks.fetchItemImages,
    fetchItemLedgers: apiMocks.fetchItemLedgers,
    fetchItemYahooListings: apiMocks.fetchItemYahooListings,
    fetchVenues: apiMocks.fetchVenues,
    updateItem: apiMocks.updateItem,
    voidItem: apiMocks.voidItem,
    deleteItem: apiMocks.deleteItem,
    deleteItemImage: apiMocks.deleteItemImage,
    reorderItemImages: apiMocks.reorderItemImages,
    adjustItem: apiMocks.adjustItem,
  }
})

import ItemDetailView from './ItemDetailView.vue'
import ItemAdjustDialog from './ItemAdjustDialog.vue'
import { i18n } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/utils/api'
import type {
  ItemLedgerRow,
  ItemResponse,
  MeResponse,
  YahooListingRow,
} from '@/utils/api'

/**
 * 商品详情（M5-①）：四段式全字段+分歧徽标三维度（会场码/月/档位字母，
 * D-063 快照单模式界面落点）+编辑弹层全量 PUT（null=清空）+409000 冲突重读
 * +作废→?reEntry= 深链+管理员删除回列表+历史两表懒加载（列探测 D-056 兜底）。
 */

const meEditor: MeResponse = {
  id: 2,
  username: 'editor',
  displayName: '編集者',
  role: 2,
  locale: 'ja-JP',
  mustChangePwd: false,
}

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

function item(overrides: Partial<ItemResponse> = {}): ItemResponse {
  return {
    id: 1,
    itemCode: 'HT9-A1X',
    venueId: 1,
    venueCode: 'HT',
    buyMonth: 1,
    seqPrefix: 'A',
    seqNo: 1,
    buyDate: '2026-01-15',
    photoDate: null,
    purchasePrice: 1000,
    fee: null,
    shippingFee: null,
    tax: null,
    soldPrice: null,
    totalCost: 1000,
    profit: null,
    priceBandCode: 'X',
    warehouse: 1,
    shelfNo: null,
    warehouseInDate: null,
    groupNo: null,
    remark: null,
    itemName: null,
    category: null,
    authorKiln: null,
    sizeText: null,
    weightG: null,
    salesChannel: null,
    stockStatus: 0,
    saleStatus: 0,
    voided: false,
    voidReason: null,
    reEntryOf: null,
    deleted: false,
    version: 3,
    createdAt: '2026-09-01 10:30:00',
    ...overrides,
  }
}

function ledgerRow(overrides: Partial<ItemLedgerRow> = {}): ItemLedgerRow {
  return {
    id: 10,
    txnType: 1,
    whFrom: null,
    whTo: 1,
    qtyChange: 0,
    stockFrom: null,
    stockTo: 0,
    saleFrom: null,
    saleTo: 0,
    reason: null,
    operatorName: 'editor',
    createdAt: '2026-09-01 10:30:00',
    ...overrides,
  }
}

function listingRow(overrides: Partial<YahooListingRow> = {}): YahooListingRow {
  return {
    id: 20,
    orderId: '10004866',
    yahooAuctionId: 'm100000',
    listPrice: null,
    soldPrice: 8000,
    status: 2,
    listedAt: null,
    closedAt: '2026-09-12 22:30:00',
    ...overrides,
  }
}

const VENUES = [
  { id: 1, code: 'HT', name: '飛騨古美術市', enabled: true },
  { id: 2, code: 'TK', name: '東京骨董市', enabled: true },
]

function button(wrapper: VueWrapper, text: string) {
  const target = wrapper.findAll('button').find((b) => b.text() === text)
  expect(target, `button ${text} should exist`).toBeTruthy()
  return target!
}

/**
 * 弹层字段定位（label 文案在三个弹层间唯一——棚番号/備考/商品名仅编辑弹层使用）。
 * 弹层已成独立组件，字段类走全局 .kcgl-field（scoped 样式不跨组件边界，D-119）。
 */
function field(wrapper: VueWrapper, label: string) {
  const target = wrapper.findAll('.kcgl-field').find((f) => f.text().includes(label))
  expect(target, `field ${label} should exist`).toBeTruthy()
  return target!
}

async function mountView(
  role: 1 | 2 | 3 = 2,
  overrides: Partial<ItemResponse> = {},
): Promise<{ wrapper: VueWrapper; router: Router }> {
  const auth = useAuthStore()
  auth.me = role === 1 ? meAdmin : role === 2 ? meEditor : meViewer
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/items', name: 'items', component: { template: '<div />' } },
      { path: '/items/:id', name: 'item-detail', component: { template: '<div />' } },
      { path: '/entry', name: 'entry', component: { template: '<div />' } },
    ],
  })
  await router.push({ name: 'item-detail', params: { id: '1' } })
  apiMocks.fetchItem.mockResolvedValue(item(overrides))
  const wrapper = mount(ItemDetailView, {
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
  apiMocks.fetchItemImages.mockResolvedValue([])
  apiMocks.fetchItemLedgers.mockResolvedValue({ rows: [] })
  apiMocks.fetchItemYahooListings.mockResolvedValue({ rows: [] })
  apiMocks.fetchVenues.mockResolvedValue(VENUES)
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('item detail view (M5-1)', () => {
  it('renders four sections, tags, seq text, and the photo strip', async () => {
    apiMocks.fetchItemImages.mockResolvedValue([
      { id: 50, clientUuid: 'u1', itemId: 1, url: '/img/a.jpg', thumbUrl: '/thumb/a.jpg', imageType: 1, sortOrder: 0 },
    ])
    const { wrapper } = await mountView(2, {
      itemName: '備前茶碗',
      saleStatus: 2,
      soldPrice: 8000,
      profit: 6800,
      shelfNo: 'A-01',
    })

    expect(wrapper.find('.itemd-code').text()).toBe('HT9-A1X')
    // 标题行：移動中（中性）+落札済み（green）双标签
    expect(wrapper.find('.itemd-title .itemd-tag.is-neutral').text()).toBe('移動中')
    expect(wrapper.find('.itemd-title .itemd-tag.is-success').text()).toBe('落札済み')
    const sectionTitles = wrapper.findAll('.el-descriptions__title').map((n) => n.text())
    expect(sectionTitles).toEqual(['基本情報', '在庫・保管', '詳細情報', 'システム情報'])
    expect(wrapper.text()).toContain('備前茶碗')
    expect(wrapper.text()).toContain('￥8,000')
    expect(wrapper.text()).toContain('棚番号')
    expect(wrapper.text()).toContain('A-01')
    // 発番情報=快照人读串（会场名+月+序号+价格档）
    expect(wrapper.text()).toContain('飛騨古美術市 1月 A1 価格帯 X')
    // 照片区：1 枚缩略图可见
    expect(wrapper.find('.itemd-photos-title').text()).toBe('写真 1 枚')
    expect(wrapper.find('.itemd-photo').exists()).toBe(true)
    // 无分歧
    expect(wrapper.find('.itemd-divergence').exists()).toBe(false)
  })

  it('renders an unset photo date as 未撮影 and a set one as JST Y/M/D (acceptance 13)', async () => {
    // 只取撮影日那一格的值（buyDate 同为 2026-01-15 且同格式渲染，全文断言会假过；
    // border 模式下 label 与 content 各占一个 .el-descriptions__cell，值在后一格）
    const photoCellText = (view: VueWrapper) => {
      const cells = view.findAll('.el-descriptions__cell')
      const labelIndex = cells.findIndex((cell) => cell.text().includes('撮影日'))
      return cells[labelIndex + 1]?.text() ?? ''
    }

    const unset = await mountView(2, { photoDate: null })
    expect(photoCellText(unset.wrapper)).toBe('未撮影')

    const set = await mountView(2, { photoDate: '2026-01-15' })
    expect(photoCellText(set.wrapper)).toBe('2026/01/15')
  })

  it('shows the divergence badge for venue, date, or band mismatch but not when aligned', async () => {
    const aligned = await mountView(2, { venueId: 1, venueCode: 'HT', buyDate: '2026-01-15', priceBandCode: 'X' })
    expect(aligned.wrapper.find('.itemd-divergence').exists()).toBe(false)
    await aligned.wrapper.unmount()

    // ① 档位字母：号尾 X vs 现档 Y（改价后服务端重推导）
    const band = await mountView(2, { priceBandCode: 'Y' })
    expect(band.wrapper.find('.itemd-divergence').text())
      .toBe('管理番号発行時の内容と現在の値が異なります')
    await band.wrapper.unmount()

    // ② 月：号内 1 vs 落札日 2026-02
    const date = await mountView(2, { buyDate: '2026-02-15' })
    expect(date.wrapper.find('.itemd-divergence').exists()).toBe(true)
    await date.wrapper.unmount()

    // ②-b 跨年同月不徽标（D-068：号内只含月不含年——2027-01 与号内 1 月一致）
    const crossYear = await mountView(2, { buyDate: '2027-01-15' })
    expect(crossYear.wrapper.find('.itemd-divergence').exists()).toBe(false)
    await crossYear.wrapper.unmount()

    // ③ 会场：号内 HT vs 现会场 TK
    const venue = await mountView(2, { venueId: 2 })
    expect(venue.wrapper.find('.itemd-divergence').exists()).toBe(true)
  })

  it('shows not-found and load-failed states with recovery', async () => {
    apiMocks.fetchItem.mockRejectedValueOnce(new ApiError(404001, 'not found'))
    const missing = await mountView()
    expect(missing.wrapper.find('.itemd-state').text()).toContain('この商品は存在しないか、削除されています。')

    apiMocks.fetchItem.mockReset()
    apiMocks.fetchItem.mockRejectedValueOnce(new ApiError(0, 'network down'))
    const failed = await mountView()
    expect(failed.wrapper.find('.itemd-state').text()).toContain('商品詳細の読み込みに失敗しました。')
    await button(failed.wrapper, '再読み込み').trigger('click')
    await flushPromises()
    expect(apiMocks.fetchItem).toHaveBeenCalledTimes(2)
  })

  it('lazy loads the ledger and listing tables with guarded dynamic keys', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const { wrapper } = await mountView()

    expect(apiMocks.fetchItemLedgers).not.toHaveBeenCalled()
    expect(apiMocks.fetchItemYahooListings).not.toHaveBeenCalled()

    apiMocks.fetchItemLedgers.mockResolvedValue({ rows: [ledgerRow()] })
    await wrapper.findAll('.el-tabs__item').find((n) => n.text() === '取引履歴')!.trigger('click')
    await flushPromises()
    expect(apiMocks.fetchItemLedgers).toHaveBeenCalledTimes(1)
    const ledgerRowEl = wrapper.find('#pane-ledger .el-table__row')
    expect(ledgerRowEl.text()).toContain('新規登録')
    expect(ledgerRowEl.text()).toContain('名古屋倉庫')
    expect(ledgerRowEl.text()).toContain('editor')

    apiMocks.fetchItemYahooListings.mockResolvedValue({ rows: [listingRow()] })
    await wrapper.findAll('.el-tabs__item').find((n) => n.text() === 'ヤフー受注')!.trigger('click')
    await flushPromises()
    expect(apiMocks.fetchItemYahooListings).toHaveBeenCalledTimes(1)
    const listingRowEl = wrapper.find('#pane-listing .el-table__row')
    expect(listingRowEl.text()).toContain('10004866')
    expect(listingRowEl.text()).toContain('m100000')
    expect(listingRowEl.text()).toContain('￥8,000')
    expect(listingRowEl.text()).toContain('落札済み')

    // 空态文案 + 空表也走列探测：动态 i18n key 不得刷 missing-key 告警（D-056）
    apiMocks.fetchItemLedgers.mockResolvedValue({ rows: [] })
    await wrapper.findAll('.el-tabs__item').find((n) => n.text() === '取引履歴')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('#pane-ledger').text()).toContain('取引履歴はありません')
    const warnings = warnSpy.mock.calls.map((call) => String(call[0]))
    warnSpy.mockRestore()
    expect(warnings.filter((message) => message.includes('common.warehouse'))).toHaveLength(0)
    expect(warnings.filter((message) => message.includes('scan.stock'))).toHaveLength(0)
    expect(warnings.filter((message) => message.includes('scan.sale'))).toHaveLength(0)
    expect(warnings.filter((message) => message.includes('items.ledger'))).toHaveLength(0)
  })

  it('renders the ledger reason in the active language, falling back for manual reasons', async () => {
    const { wrapper } = await mountView()
    apiMocks.fetchItemLedgers.mockResolvedValue({
      rows: [
        // 系统生成理由（V7，D-130）：后端同时落了键+参数，按当前语言渲染
        ledgerRow({
          id: 11,
          txnType: 7,
          reason: '棚卸調整 PD2026100101',
          reasonCode: 'ledgers.reason.stocktakeAdjust',
          reasonParams: '{"no":"PD2026100101"}',
        }),
        // 人工理由（报废/调拨备注）：无键，原样显示
        ledgerRow({ id: 12, txnType: 4, reason: '割れのため' }),
        // 历史行（V7 之前）：无键，回退日文原文
        ledgerRow({ id: 13, txnType: 7, reason: '棚卸調整 PD2026090101' }),
      ],
    })
    await wrapper.findAll('.el-tabs__item').find((n) => n.text() === '取引履歴')!.trigger('click')
    await flushPromises()

    const rows = wrapper.findAll('#pane-ledger .el-table__row')
    expect(rows[0].text()).toContain('棚卸調整 PD2026100101')
    expect(rows[1].text()).toContain('割れのため')
    expect(rows[2].text()).toContain('棚卸調整 PD2026090101')

    // 切语言后系统理由跟着走，人工理由与历史行不动
    i18n.global.locale.value = 'zh-CN'
    await flushPromises()
    const switched = wrapper.findAll('#pane-ledger .el-table__row')
    expect(switched[0].text()).toContain('盘点调整 PD2026100101')
    expect(switched[1].text()).toContain('割れのため')
    expect(switched[2].text()).toContain('棚卸調整 PD2026090101')
    i18n.global.locale.value = 'ja-JP'
  })

  it('shows re-entry links from structured columns instead of the remark marker', async () => {
    const { wrapper, router } = await mountView(2, {
      remark: '客からの預かり品',
      reEntryOf: 9,
      reEntryOfCode: 'HT9-Z9X',
      voidReEntry: 30,
      voidReEntryCode: 'HT9-Z30X',
    })

    // 备注是用户数据，不再被后端塞进「再登録元/先」日文标记
    expect(wrapper.text()).toContain('客からの預かり品')
    const items = wrapper.findAll('.el-descriptions__label')
    const labels = items.map((n) => n.text())
    expect(labels).toContain('再登録元')
    expect(labels).toContain('再登録先')

    const links = wrapper.findAll('.itemd-link')
    expect(links).toHaveLength(2)
    expect(links[0].text()).toBe('HT9-Z9X')
    expect(links[1].text()).toBe('HT9-Z30X')

    // 互链跳转：对端 id 进详情（同组件复用，参数变化触发重载）
    await links[1].trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/items/30')
  })

  it('hides the re-entry rows when the structured columns are absent', async () => {
    const { wrapper } = await mountView()

    expect(wrapper.findAll('.el-descriptions__label').map((n) => n.text())).not.toContain('再登録元')
    expect(wrapper.findAll('.itemd-link')).toHaveLength(0)
  })

  it('submits a full-payload edit with null-clearing and reloads in place', async () => {
    const { wrapper } = await mountView(2, { shelfNo: 'A-01', remark: '元の備考' })

    await button(wrapper, '編集する').trigger('click')
    await flushPromises()
    expect(wrapper.find('.el-dialog').text()).toContain('管理番号は変更されません')
    // 弹层预填：棚番号现值带入
    expect(field(wrapper, '棚番号').find('input').element.value).toBe('A-01')

    // 棚番号清空（→ null）、備考改写（trim）
    await field(wrapper, '棚番号').find('input').setValue('')
    await field(wrapper, '備考').find('textarea').setValue('  E2E新品  ')
    await button(wrapper, '保存する').trigger('click')
    await flushPromises()

    // 全量 PUT：必填四项原样 + 可选字段 null=清空 + 文本 trim
    expect(apiMocks.updateItem).toHaveBeenCalledWith(1, expect.objectContaining({
      version: 3,
      venueId: 1,
      buyDate: '2026-01-15',
      purchasePrice: 1000,
      warehouse: 1,
      shelfNo: null,
      remark: 'E2E新品',
      weightG: null,
      photoDate: null,
    }))
    // 成功后原地重读（拿新 version+生成列）
    expect(apiMocks.fetchItem).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.kcgl-form-error').exists()).toBe(false)
  })

  it('keeps the form open and reloads on a 409000 version conflict', async () => {
    const { wrapper } = await mountView(2, { remark: '元の備考' })

    await button(wrapper, '編集する').trigger('click')
    await flushPromises()
    await field(wrapper, '備考').find('textarea').setValue('競合中の修正')

    apiMocks.updateItem.mockRejectedValue(new ApiError(409000, 'conflict'))
    await button(wrapper, '保存する').trigger('click')
    await flushPromises()

    expect(wrapper.find('.kcgl-form-error').text())
      .toBe('他の人がこの商品を更新しています。最新の内容を再読み込みしてください。')
    // 冲突即重读刷新 version（输入保留可直接再提交）
    expect(apiMocks.fetchItem).toHaveBeenCalledTimes(2)
    expect(field(wrapper, '備考').find('textarea').element.value).toBe('競合中の修正')
  })

  it('requires a void reason and deep-links to re-entry on success', async () => {
    const { wrapper, router } = await mountView(2)

    await button(wrapper, '取り消して再登録').trigger('click')
    await flushPromises()
    await button(wrapper, '取り消して再入力へ').trigger('click')
    await flushPromises()
    expect(wrapper.find('.kcgl-form-error').text()).toBe('取り消し理由を入力してください')

    await wrapper.find('.el-dialog textarea').setValue('E2E価格入力ミス')
    apiMocks.voidItem.mockResolvedValue(item())
    await button(wrapper, '取り消して再入力へ').trigger('click')
    await flushPromises()

    expect(apiMocks.voidItem).toHaveBeenCalledWith(1, expect.any(String), 'E2E価格入力ミス')
    expect(router.currentRoute.value.name).toBe('entry')
    expect(router.currentRoute.value.query.reEntry).toBe('1')
  })

  it('admin deletes with an optional reason; editor and viewer get no delete button', async () => {
    const { wrapper, router } = await mountView(1)

    await button(wrapper, '削除する').trigger('click')
    await flushPromises()
    expect(wrapper.find('.el-dialog').text()).toContain('削除済みの商品は「削除済み商品」から復元できます')
    await wrapper.find('.el-dialog textarea').setValue('E2E整理')
    apiMocks.deleteItem.mockResolvedValue(undefined)
    // footer 确认键（头部同名按钮会重开弹层清空理由——必须弹层内定位）
    await wrapper.findAll('.el-dialog button').find((b) => b.text() === '削除する')!.trigger('click')
    await flushPromises()

    expect(apiMocks.deleteItem).toHaveBeenCalledWith(1, expect.any(String), 'E2E整理')
    expect(router.currentRoute.value.name).toBe('items')

    const editor = await mountView(2)
    expect(editor.wrapper.findAll('button').filter((b) => b.text() === '削除する')).toHaveLength(0)
    const viewer = await mountView(3)
    // viewer 三按钮全无（只读详情）
    expect(viewer.wrapper.findAll('button').filter((b) => b.text() === '編集する')).toHaveLength(0)
    expect(viewer.wrapper.findAll('button').filter((b) => b.text() === '取り消して再登録')).toHaveLength(0)
    expect(viewer.wrapper.findAll('button').filter((b) => b.text() === '削除する')).toHaveLength(0)
    expect(viewer.wrapper.find('.itemd-code').text()).toBe('HT9-A1X')
  })

  it('admin corrects status via the adjust dialog; editor and viewer get no button (D4)', async () => {
    const { wrapper } = await mountView(1, { stockStatus: 1, saleStatus: 0 })
    expect(apiMocks.fetchItem).toHaveBeenCalledTimes(1)

    await button(wrapper, '状態を修正する').trigger('click')
    await flushPromises()
    expect(wrapper.find('.el-dialog').text()).toContain('在庫状態・販売状態の修正')

    // 弹层内改一轴并提交：本用例要证的是父组件的接线——成功后 @adjusted 触发详情重载
    const dialog = wrapper.findComponent(ItemAdjustDialog)
    dialog.findAllComponents('.el-select')[0]!.vm.$emit('update:modelValue', 2)
    await wrapper.find('.el-dialog textarea').setValue('実物は出庫済み')
    await wrapper.findAll('.el-dialog button').find((b) => b.text() === '修正する')!.trigger('click')
    await flushPromises()

    expect(apiMocks.adjustItem).toHaveBeenCalledWith(1, {
      clientReqId: expect.any(String),
      reason: '実物は出庫済み',
      stockStatus: 2,
    })
    expect(apiMocks.fetchItem).toHaveBeenCalledTimes(2)

    // 手工修正是 A-only：编辑者与閲覧者都不该看到入口
    const editor = await mountView(2)
    expect(editor.wrapper.findAll('button').filter((b) => b.text() === '状態を修正する')).toHaveLength(0)
    const viewer = await mountView(3)
    expect(viewer.wrapper.findAll('button').filter((b) => b.text() === '状態を修正する')).toHaveLength(0)
  })

  it('renders the voided banner and hides actions for a voided item', async () => {
    const { wrapper } = await mountView(2, { voided: true, voidReason: '誤登録' })

    expect(wrapper.find('.kcgl-info-box').text()).toBe('取り消し済みの商品です（理由：誤登録）。')
    expect(wrapper.find('.itemd-title .itemd-tag.is-danger').text()).toBe('取り消し済み')
    // 操作区容器由 AppPageHeader 提供、恒在（空则不占位），故断其内无按钮而非容器不存在
    expect(wrapper.find('.page-header-actions button').exists()).toBe(false)
  })
})

describe('photo unbind and reorder (D5)', () => {
  function photo(id: number, name: string) {
    return {
      id,
      clientUuid: `u${id}`,
      itemId: 1,
      url: `/img/${name}.jpg`,
      thumbUrl: `/thumb/${name}.jpg`,
      imageType: 1,
      sortOrder: id,
    }
  }

  /** 照片条当前顺序（缩略图 src 的文件名序列）——重排的可见结果。 */
  function stripOrder(wrapper: VueWrapper): string[] {
    return wrapper.findAll('.itemd-photos img').map((n) => n.attributes('src')!.replace('/thumb/', ''))
  }

  function toolButtons(wrapper: VueWrapper, itemIndex: number) {
    return wrapper.findAll('.itemd-photo-item')[itemIndex].findAll('.itemd-photo-tool')
  }

  beforeEach(() => {
    apiMocks.fetchItemImages.mockResolvedValue([photo(50, 'a'), photo(51, 'b'), photo(52, 'c')])
    apiMocks.reorderItemImages.mockResolvedValue(undefined)
    apiMocks.deleteItemImage.mockResolvedValue(undefined)
  })

  it('editor moves a photo earlier: local order updates and the full order is persisted', async () => {
    const { wrapper } = await mountView(2)
    expect(stripOrder(wrapper)).toEqual(['a.jpg', 'b.jpg', 'c.jpg'])

    // 第 2 张的左箭头 = 前移一位
    await toolButtons(wrapper, 1)[0].trigger('click')
    await flushPromises()

    expect(stripOrder(wrapper)).toEqual(['b.jpg', 'a.jpg', 'c.jpg'])
    // 服务端要全量一致集合：提交的是完整顺序，不是被移动的那一张
    expect(apiMocks.reorderItemImages).toHaveBeenCalledWith(1, [51, 50, 52])
  })

  it('rolls the local order back and reports when the reorder request fails', async () => {
    apiMocks.reorderItemImages.mockRejectedValue(new ApiError(409000, 'conflict'))
    const { wrapper } = await mountView(2)

    await toolButtons(wrapper, 1)[0].trigger('click')
    await flushPromises()

    expect(stripOrder(wrapper)).toEqual(['a.jpg', 'b.jpg', 'c.jpg'])
    expect(wrapper.find('.itemd-photo-error').text()).toBe(i18n.global.t('errors.409000'))
  })

  it('disables the end arrows: the first cannot move earlier, the last cannot move later', async () => {
    const { wrapper } = await mountView(2)

    expect(toolButtons(wrapper, 0)[0].attributes('disabled')).toBeDefined()
    expect(toolButtons(wrapper, 0)[1].attributes('disabled')).toBeUndefined()
    expect(toolButtons(wrapper, 2)[1].attributes('disabled')).toBeDefined()
    expect(toolButtons(wrapper, 2)[0].attributes('disabled')).toBeUndefined()
  })

  it('editor unbinds a photo through the confirm dialog', async () => {
    const { wrapper } = await mountView(2)

    await toolButtons(wrapper, 1)[2].trigger('click')
    await flushPromises()
    expect(wrapper.find('.el-dialog').text()).toContain('元の画像ファイルはサーバーに残ります')
    await wrapper.findAll('.el-dialog button').find((b) => b.text() === '外す')!.trigger('click')
    await flushPromises()

    expect(apiMocks.deleteItemImage).toHaveBeenCalledWith(51)
    expect(stripOrder(wrapper)).toEqual(['a.jpg', 'c.jpg'])
    expect(wrapper.text()).toContain('写真 2 枚')
  })

  it('viewer gets no photo tools at all', async () => {
    const { wrapper } = await mountView(3)

    expect(wrapper.findAll('.itemd-photo-item')).toHaveLength(3)
    expect(wrapper.findAll('.itemd-photo-tool')).toHaveLength(0)
  })
})

/** 等真实历史（popstate 派发为宏任务）落到目标路由，避免用固定 tick 数赌时序。 */
async function settleOn(router: Router, name: string): Promise<void> {
  for (let i = 0; i < 50; i += 1) {
    if (router.currentRoute.value.name === name) return
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
  throw new Error(`route did not settle on ${name}`)
}

/**
 * 戻る键（C1）：列表把筛选/页码投影进 URL（ItemsView#projectQuery），详情原路返回即带回来。
 * 用真实浏览器历史（createWebHistory）跑「退回带筛选」这一支——内存历史不维护
 * state.back（state 恒为 {}），走不到该分支会退化成"只测得到兜底"。
 */
describe('detail back navigation (C1)', () => {
  it('returns to the list with its filters and page when a back entry exists', async () => {
    useAuthStore().me = meEditor
    const router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/items', name: 'items', component: { template: '<div />' } },
        { path: '/items/:id', name: 'item-detail', component: { template: '<div />' } },
      ],
    })
    await router.push({ name: 'items', query: { kw: 'HT9', page: '3' } })
    await router.push({ name: 'item-detail', params: { id: '1' } })
    apiMocks.fetchItem.mockResolvedValue(item())
    const wrapper = mount(ItemDetailView, { global: { plugins: [i18n, router] } })
    await flushPromises()

    await wrapper.find('.page-header-back').trigger('click')
    await settleOn(router, 'items')

    expect(router.currentRoute.value.query).toEqual({ kw: 'HT9', page: '3' })
  })

  it('falls back to a bare list push when there is no back entry (deep link)', async () => {
    const { wrapper, router } = await mountView(2)

    await wrapper.find('.page-header-back').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('items')
    expect(router.currentRoute.value.query).toEqual({})
  })
})
