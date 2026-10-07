import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

const apiMocks = vi.hoisted(() => ({
  fetchYahooBatches: vi.fn(),
  uploadYahooImport: vi.fn(),
  fetchPendingShipments: vi.fn(),
  fetchYahooReconcile: vi.fn(),
  fetchYahooUnmatched: vi.fn(),
}))

// ApiError 保持真实实现（错误文案分支依赖 instanceof/code）；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchYahooBatches: apiMocks.fetchYahooBatches,
    uploadYahooImport: apiMocks.uploadYahooImport,
    fetchPendingShipments: apiMocks.fetchPendingShipments,
    fetchYahooReconcile: apiMocks.fetchYahooReconcile,
    fetchYahooUnmatched: apiMocks.fetchYahooUnmatched,
  }
})

import YahooView from './YahooView.vue'
import { i18n } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/utils/api'
import type {
  MeResponse,
  YahooImportBatch,
  YahooPendingShipment,
  YahooReconcile,
  YahooReconcileRow,
  YahooUnmatchedRows,
} from '@/utils/api'

/**
 * 雅虎联动桌面页（M5-②b 受注 xlsx）：批次历史（状态/计数/まとめ売り補注/
 * 失败计数占位）/viewer 禁传/上传后刷新历史/上传失败就地展示（409011）/
 * 处理中轮询起停/出荷待ち行内直达扫码卖出（注文番号列）/照合三视图
 * （滞留红标+近期同步降灰）/不一致行明细（D-105 展开按需取+缓存+失败重试）。
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

function batch(overrides: Partial<YahooImportBatch> = {}): YahooImportBatch {
  return {
    id: 1,
    originalFilename: 'ストア9.20(1).xlsx',
    status: 1,
    rowCount: 3,
    matchedCount: 2,
    unmatchedCount: 1,
    updatedCount: 0,
    note: null,
    noteJson: null,
    errorMessage: null,
    errorMessageCode: null,
    errorMessageParams: null,
    uploadedBy: 2,
    createdAt: '2026-09-28 09:00:00',
    finishedAt: '2026-09-28 09:00:02',
    errorRows: [],
    ...overrides,
  }
}

function shipment(): YahooPendingShipment {
  return {
    itemId: 601,
    itemCode: 'HT9-A1X',
    thumbUrl: null,
    warehouse: 1,
    shelfNo: 'A-03',
    soldPrice: 12000,
    orderId: '10004866',
    auctionId: 'auc-101',
    closedAt: '2026-09-20 21:05:33',
    delayed: true,
  }
}

function reconcileRow(overrides: Partial<YahooReconcileRow> = {}): YahooReconcileRow {
  return {
    itemId: 701,
    itemCode: 'HT9-A2X',
    warehouse: 2,
    shelfNo: null,
    soldPrice: 25000,
    orderId: '10007001',
    auctionId: 'auc-201',
    closedAt: '2026-09-01 21:00:00',
    lastSyncedAt: '2026-09-28 08:00:00',
    delayed: false,
    recentlySynced: true,
    ...overrides,
  }
}

function reconcile(): YahooReconcile {
  return {
    soldNotShipped: [reconcileRow({ delayed: true })],
    canceledNotRelisted: [],
    withdrawNeeded: [reconcileRow({ itemId: 702, recentlySynced: true })],
  }
}

/** 静默读取三份数据的默认夹具（多数用例只关心其中一个标签页）。 */
function seedReads(): void {
  apiMocks.fetchYahooBatches.mockResolvedValue([])
  apiMocks.fetchPendingShipments.mockResolvedValue({ count: 0, items: [] })
  apiMocks.fetchYahooReconcile.mockResolvedValue({
    soldNotShipped: [],
    canceledNotRelisted: [],
    withdrawNeeded: [],
  })
  apiMocks.fetchYahooUnmatched.mockResolvedValue(unmatchedOf(1, []))
}

/** 不一致行明细（D-105）：自码是受注文件原文（旧格式码照原样列出）。 */
function unmatchedOf(batchId: number, rows: YahooUnmatchedRows['rows']): YahooUnmatchedRows {
  return { batchId, total: rows.length, truncated: false, rows }
}

const unmatchedRow = (
  overrides: Partial<YahooUnmatchedRows['rows'][number]> = {},
): YahooUnmatchedRows['rows'][number] => ({
  selfCode: 'M-D8T-SR5',
  orderId: '10004876',
  auctionId: 'auc-901',
  soldPrice: 8000,
  closedAt: '2026-09-20 21:05:33',
  ...overrides,
})

async function mountView(role: 1 | 3 = 1): Promise<{ wrapper: VueWrapper; router: Router }> {
  const auth = useAuthStore()
  auth.me = role === 1 ? meAdmin : meViewer
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/yahoo', name: 'yahoo', component: { template: '<div />' } },
      { path: '/scan', name: 'scan', component: { template: '<div />' } },
    ],
  })
  await router.push({ name: 'yahoo' })
  const wrapper = mount(YahooView, {
    global: { plugins: [i18n, router] },
  })
  await flushPromises()
  return { wrapper, router }
}

/**
 * v-loading 遮罩是否已收起。不用 VTU 的 isVisible()：它在该 jsdom 版本里走
 * Element.checkVisibility()，而那个实现对 display:none 不成立，于是收起的遮罩
 * 也会被判为可见。这里直接读 v-show 写下的行内样式（遮罩节点在过渡收尾前仍在 DOM）。
 */
function isMaskHidden(wrapper: VueWrapper): boolean {
  return wrapper
    .findAll('.el-loading-mask')
    .every((mask) => (mask.attributes('style') ?? '').includes('display: none'))
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  vi.resetAllMocks()
  setActivePinia(createPinia())
  i18n.global.locale.value = 'ja-JP'
  seedReads()
})

afterEach(() => {
  vi.restoreAllMocks()
  // D-127 用例会临时切语言验证落库文案随语言走，本文件其余断言（完了/処理中/
  // 単価が未分割）都按 ja-JP 写死，必须还原，否则失败顺序一变就互相污染。
  i18n.global.locale.value = 'ja-JP'
})

describe('yahoo view (M5-②b)', () => {
  it('renders batch history with status tags and null-safe counts', async () => {
    apiMocks.fetchYahooBatches.mockResolvedValue([
      batch(),
      batch({ id: 2, status: 0, rowCount: null, matchedCount: null,
        unmatchedCount: null, updatedCount: null, finishedAt: null }),
      batch({ id: 3, status: 2, errorMessage: 'B列は「YahooAuctionMerchantId」である必要があります',
        rowCount: null, matchedCount: null, unmatchedCount: null,
        updatedCount: null, finishedAt: '2026-09-28 09:01:00',
        errorRows: [{ line: 2, raw: 'auc-502,…', reason: '落札価格が読み取れません' }] }),
    ])
    const { wrapper } = await mountView()

    const rows = wrapper.findAll('#pane-import .el-table__row')
    expect(rows).toHaveLength(3)
    expect(wrapper.text()).toContain('ストア9.20(1).xlsx')
    const tags = wrapper.findAll('#pane-import .yahoo-tag')
    expect(tags.map((tag) => tag.text())).toEqual(['完了', '処理中', '失敗'])
    // 失败/处理中批次计数未落 → 占位符；完成批次显示真实计数
    expect(rows[0]!.text()).toContain('2')
    expect(rows[1]!.text()).toContain('—')
  })

  // まとめ売り補注（D-069 4）：批次 note 在展开区呈现（単価未分割提示）
  it('shows the multi-item note in the batch expand', async () => {
    apiMocks.fetchYahooBatches.mockResolvedValue([
      batch({ note: '注文10004900（HT9-A1X・HT9-A2X）は複数商品のため単価が未分割です' }),
    ])
    const { wrapper } = await mountView()

    await wrapper.find('#pane-import .el-table__expand-icon').trigger('click')
    await flushPromises()

    expect(wrapper.find('#pane-import .yahoo-detail .kcgl-info-box').text())
      .toContain('単価が未分割')
  })

  // D-127：批次级失败提示走 code+params（历史批次无 code 才回退日文原文）
  it('renders a structured batch failure in the active locale', async () => {
    apiMocks.fetchYahooBatches.mockResolvedValue([
      batch({ id: 4, status: 2, rowCount: null, matchedCount: null, unmatchedCount: null,
        updatedCount: null, finishedAt: '2026-09-28 09:01:00',
        errorMessage: '2列は「YahooAuctionMerchantId」であるべきですが「商品コード」です',
        errorMessageCode: 'imports.batch.yahooHeaderMismatch',
        errorMessageParams: JSON.stringify({ column: 'B', expected: 'YahooAuctionMerchantId', actual: '商品コード' }) }),
    ])
    i18n.global.locale.value = 'zh-CN'
    const { wrapper } = await mountView()

    await wrapper.find('#pane-import .el-table__expand-icon').trigger('click')
    await flushPromises()

    const shown = wrapper.find('#pane-import .yahoo-detail .kcgl-error-box').text()
    expect(shown).toContain('B列的表头应为「YahooAuctionMerchantId」，实际是「商品コード」')
    expect(shown).not.toContain('であるべきですが')
  })

  // D-127：错误行原因同样随语言切换（落库 reason 是日文兜底，只在历史行可见）
  it('renders a structured error row reason in the active locale', async () => {
    apiMocks.fetchYahooBatches.mockResolvedValue([
      batch({ id: 5, status: 2, rowCount: null, matchedCount: null, unmatchedCount: null,
        updatedCount: null, finishedAt: '2026-09-28 09:01:00',
        errorRows: [{ line: 2, raw: 'abc,…', reason: '落札価格を数値として解釈できません: abc',
          code: 'imports.reason.soldPriceNotNumber', params: { raw: 'abc' } }] }),
    ])
    i18n.global.locale.value = 'zh-CN'
    const { wrapper } = await mountView()

    await wrapper.find('#pane-import .el-table__expand-icon').trigger('click')
    await flushPromises()

    const reason = wrapper.find('#pane-import .yahoo-errors-table .el-table__row').text()
    expect(reason).toContain('成交价格不是有效的数字：abc')
  })

  // D-127：まとめ売り補注走 note_json 数组，逐条按当前语言渲染后用当语言连接符重连
  it('renders the structured multi-item note in the active locale', async () => {
    apiMocks.fetchYahooBatches.mockResolvedValue([
      batch({ note: '注文10004900（HT9-A1X・HT9-A2X）は複数商品のため単価が未分割です',
        noteJson: JSON.stringify([{
          code: 'imports.note.multiItemByOrder',
          params: { orderId: '10004900', codes: 'HT9-A1X・HT9-A2X' },
          text: '注文10004900（HT9-A1X・HT9-A2X）は複数商品のため単価が未分割です',
        }]) }),
    ])
    i18n.global.locale.value = 'en-US'
    const { wrapper } = await mountView()

    await wrapper.find('#pane-import .el-table__expand-icon').trigger('click')
    await flushPromises()

    const shown = wrapper.find('#pane-import .yahoo-detail .kcgl-info-box').text()
    expect(shown).toBe('Order 10004900 (HT9-A1X・HT9-A2X) contains several items, so the unit price was not split')
    expect(shown).not.toContain('単価が未分割')
  })

  it('viewer cannot upload and sees the role note', async () => {
    const { wrapper } = await mountView(3)

    const uploadButton = wrapper.find('.yahoo-upload button')
    expect(uploadButton.attributes('disabled')).toBeDefined()
    expect(wrapper.find('.yahoo-upload .kcgl-info-box').text()).toBe(
      'インポートには編集者以上の権限が必要です。',
    )
  })

  // 回归：el-table-column 以 {row:{}} 探测嵌套列时逐列调用默认插槽
  // （element-plus table-column TableColumnRenderer），动态 i18n key 必须
  // 空值兜底，否则空数据页也刷 common.warehouse.undefined 告警（E2E 实录）。
  it('renders warehouse cells without missing-key warnings on the column probe pass', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const { wrapper } = await mountView(3)
    expect(wrapper.exists()).toBe(true)
    // mockRestore 会清空 mock.calls——先快照再还原
    const warnings = warnSpy.mock.calls.map((call) => String(call[0]))
    warnSpy.mockRestore()
    expect(warnings.filter((message) => message.includes('common.warehouse'))).toHaveLength(0)
  })

  it('uploads the chosen order file and refreshes the history', async () => {
    const { wrapper } = await mountView()
    expect(apiMocks.fetchYahooBatches).toHaveBeenCalledTimes(1)

    apiMocks.uploadYahooImport.mockResolvedValue(batch({ status: 0 }))
    const input = wrapper.find('.yahoo-upload-input')
    const file = new File(['PK…'], 'ストア9.20(1).xlsx', {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    })
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    await flushPromises()

    expect(apiMocks.uploadYahooImport).toHaveBeenCalledTimes(1)
    const form = apiMocks.uploadYahooImport.mock.calls[0]![0] as FormData
    expect(form.get('file')).toBe(file)
    expect(apiMocks.fetchYahooBatches).toHaveBeenCalledTimes(2)
    // 上传成功后错误清空、输入复位（同文件可再次触发 change）
    expect(wrapper.find('.yahoo-upload .kcgl-error-box').exists()).toBe(false)
  })

  it('upload failure (sha duplicate) shows the mapped message in place', async () => {
    const { wrapper } = await mountView()

    apiMocks.uploadYahooImport.mockRejectedValue(new ApiError(409011, 'duplicate'))
    const input = wrapper.find('.yahoo-upload-input')
    const file = new File(['PK…'], 'ストア9.20(1).xlsx', {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    })
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    await flushPromises()

    expect(wrapper.find('.yahoo-upload .kcgl-error-box').text()).toBe(
      i18n.global.t('errors.409011'),
    )
  })

  it('starts polling while a batch is processing and stops at terminal state', async () => {
    const setIntervalSpy = vi.spyOn(window, 'setInterval')
    const clearIntervalSpy = vi.spyOn(window, 'clearInterval')
    apiMocks.fetchYahooBatches
      .mockResolvedValueOnce([batch({ status: 0 })])
      .mockResolvedValueOnce([batch({ status: 1 })])
    const { wrapper } = await mountView()

    expect(setIntervalSpy).toHaveBeenCalledWith(expect.any(Function), 2000)

    // 手动触发一次轮询回调：批次到终态后应停止
    const tick = setIntervalSpy.mock.calls[0]![0] as () => void
    tick()
    await flushPromises()

    expect(apiMocks.fetchYahooBatches).toHaveBeenCalledTimes(2)
    expect(clearIntervalSpy).toHaveBeenCalled()
    expect(wrapper.find('#pane-import .yahoo-tag').text()).toBe('完了')
  })

  // 回归（D-070 回声抑制附带+E2E yahoo.spec desktop 实录「出荷待ち 0 件」停旧）：
  // 自己的 YAHOO_IMPORT 广播被抑制后，出荷待ち/照合的收敛主路径=批次轮询见证
  // 终态——轮询停止时必须补齐另两份数据，否则导入者自己的页面停在导入前旧值。
  it('reloads shipments and reconcile when the poll observes batch terminal state', async () => {
    const setIntervalSpy = vi.spyOn(window, 'setInterval')
    apiMocks.fetchYahooBatches
      .mockResolvedValueOnce([batch({ status: 0 })])
      .mockResolvedValueOnce([batch({ status: 1 })])
    apiMocks.fetchPendingShipments.mockResolvedValue({ count: 1, items: [shipment()] })
    const { wrapper } = await mountView()

    const tick = setIntervalSpy.mock.calls[0]![0] as () => void
    tick()
    await flushPromises()

    // 挂载各 1 次 + 轮询见证终态补齐各 1 次；且新数据落到视图
    expect(apiMocks.fetchPendingShipments).toHaveBeenCalledTimes(2)
    expect(apiMocks.fetchYahooReconcile).toHaveBeenCalledTimes(2)
    expect(wrapper.find('#pane-shipments .yahoo-section-count').text()).toBe('出荷待ち 1 件')
  })

  // 回归（同上，竞态分支）：上传响应回来时批次已全部终态（处理极快、
  // 轮询从未启动）→ onFileChange 直接收敛另两份数据。
  it('reloads shipments and reconcile after upload when all batches are already terminal', async () => {
    apiMocks.uploadYahooImport.mockResolvedValue(batch({ status: 1 }))
    apiMocks.fetchYahooBatches.mockResolvedValue([batch({ status: 1 })])
    apiMocks.fetchPendingShipments.mockResolvedValue({ count: 1, items: [shipment()] })
    const { wrapper } = await mountView()

    const input = wrapper.find('.yahoo-upload-input')
    const file = new File(['PK…'], 'ストア9.20(1).xlsx', {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    })
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    await flushPromises()

    // 挂载 1 次 + 上传后直接补齐 1 次（全程无处理中批次、轮询未启动）
    expect(apiMocks.fetchPendingShipments).toHaveBeenCalledTimes(2)
    expect(apiMocks.fetchYahooReconcile).toHaveBeenCalledTimes(2)
    expect(wrapper.find('#pane-shipments .yahoo-section-count').text()).toBe('出荷待ち 1 件')
  })

  it('renders the shipment queue with a direct sell deep link', async () => {
    apiMocks.fetchPendingShipments.mockResolvedValue({ count: 1, items: [shipment()] })
    const { wrapper, router } = await mountView()

    const row = wrapper.find('#pane-shipments .el-table__row')
    expect(row.text()).toContain('HT9-A1X')
    expect(row.text()).toContain('￥12,000')
    expect(row.text()).toContain('10004866')
    expect(row.text()).toContain('出荷遅延')
    expect(wrapper.find('#pane-shipments .yahoo-section-count').text()).toBe('出荷待ち 1 件')

    await wrapper.find('#pane-shipments .el-table__row button').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('scan')
    expect(router.currentRoute.value.query.code).toBe('HT9-A1X')
  })

  it('renders the three reconcile views with delayed and recently-synced marks', async () => {
    apiMocks.fetchYahooReconcile.mockResolvedValue(reconcile())
    const { wrapper } = await mountView()

    const pane = wrapper.find('#pane-reconcile')
    expect(pane.findAll('.el-table__row')).toHaveLength(2)
    expect(pane.text()).toContain('落札済み・未出庫（1）')
    expect(pane.text()).toContain('落札なし・再出品待ち（0）')
    expect(pane.text()).toContain('出庫済み・ヤフー出品中（取り下げ確認）（1）')
    expect(pane.text()).toContain('滞留')
    expect(pane.text()).toContain('直近で同期済み')
  })

  it('shows load errors per pane with a reload action', async () => {
    apiMocks.fetchYahooBatches
      .mockRejectedValueOnce(new ApiError(0, 'network down'))
      .mockResolvedValueOnce([batch()])
    const { wrapper } = await mountView()

    const errorBox = wrapper.find('#pane-import .kcgl-error-box')
    expect(errorBox.text()).toContain('network down')

    await errorBox.find('button').trigger('click')
    await flushPromises()

    expect(apiMocks.fetchYahooBatches).toHaveBeenCalledTimes(2)
    expect(wrapper.find('#pane-import .kcgl-error-box').exists()).toBe(false)
  })

  // 空表 = 本页最常态（还没导入过），此时必须撤遮罩。曾把遮罩条件写成数据派生
  // （batches.length === 0 && !batchesError）→ 空列表取回后条件恒真，用户看到"一直转圈"
  it('clears the loading mask once an empty batch list arrives', async () => {
    let resolveBatches: (rows: YahooImportBatch[]) => void = () => {}
    apiMocks.fetchYahooBatches.mockReturnValue(
      new Promise<YahooImportBatch[]>((resolve) => {
        resolveBatches = resolve
      }),
    )
    const { wrapper } = await mountView()

    // 请求在途：唯一该转圈的时刻
    expect(isMaskHidden(wrapper)).toBe(false)

    resolveBatches([])
    await flushPromises()

    expect(isMaskHidden(wrapper)).toBe(true)
    expect(wrapper.find('#pane-import .empty-state').exists()).toBe(true)
  })
})

/**
 * 不一致行明细（D-105）：「受注有而システム无」在 D-105 之前只有一个计数，
 * 45 行原文自码无处可查。展开批次按需取明细（不在列表里带，避免 N+1）。
 */
describe('yahoo unmatched rows (D-105)', () => {
  async function expand(wrapper: VueWrapper): Promise<void> {
    await wrapper.find('#pane-import .el-table__expand-icon').trigger('click')
    await flushPromises()
  }

  it('lists unmatched rows with the raw self-code on expand', async () => {
    apiMocks.fetchYahooBatches.mockResolvedValue([batch({ id: 7, unmatchedCount: 2 })])
    apiMocks.fetchYahooUnmatched.mockResolvedValue(
      unmatchedOf(7, [unmatchedRow(), unmatchedRow({ selfCode: 'M-A68L-KW4', orderId: '10004895' })]),
    )
    const { wrapper } = await mountView()

    // 未展开不取：明细是按需的，批次列表本身不带行
    expect(apiMocks.fetchYahooUnmatched).not.toHaveBeenCalled()

    await expand(wrapper)

    expect(apiMocks.fetchYahooUnmatched).toHaveBeenCalledTimes(1)
    expect(apiMocks.fetchYahooUnmatched).toHaveBeenCalledWith(7)
    const detail = wrapper.find('#pane-import .yahoo-detail')
    expect(detail.text()).toContain('不一致行')
    expect(detail.text()).toContain('M-D8T-SR5')
    expect(detail.text()).toContain('M-A68L-KW4')
    expect(detail.text()).toContain('10004876')
    expect(detail.text()).toContain('￥8,000')
    expect(detail.find('.yahoo-unmatched-total').text()).toBe('2 件')
  })

  it('does not request the detail when the batch has no unmatched rows', async () => {
    apiMocks.fetchYahooBatches.mockResolvedValue([batch({ id: 8, unmatchedCount: 0 })])
    const { wrapper } = await mountView()

    await expand(wrapper)

    expect(apiMocks.fetchYahooUnmatched).not.toHaveBeenCalled()
    const detail = wrapper.find('#pane-import .yahoo-detail')
    expect(detail.find('.yahoo-unmatched-total').exists()).toBe(false)
    expect(detail.text()).not.toContain('不一致行')
  })

  // 计数未知（历史批次）≠ 0：渲染与取数用同一判据，否则会出现"请求了却永不渲染"
  it('still requests the detail when the count is unknown', async () => {
    apiMocks.fetchYahooBatches.mockResolvedValue([batch({ id: 9, unmatchedCount: null })])
    apiMocks.fetchYahooUnmatched.mockResolvedValue(unmatchedOf(9, []))
    const { wrapper } = await mountView()

    await expand(wrapper)

    expect(apiMocks.fetchYahooUnmatched).toHaveBeenCalledWith(9)
    expect(wrapper.find('#pane-import .yahoo-detail').text()).toContain('不一致行')
  })

  // 批次已达终态、明细不再变：收起再展开不应重复请求（一次展开一个请求）
  it('caches the detail across collapse and re-expand', async () => {
    apiMocks.fetchYahooBatches.mockResolvedValue([batch({ id: 10, unmatchedCount: 1 })])
    apiMocks.fetchYahooUnmatched.mockResolvedValue(unmatchedOf(10, [unmatchedRow()]))
    const { wrapper } = await mountView()

    await expand(wrapper)
    await expand(wrapper)
    await expand(wrapper)

    expect(apiMocks.fetchYahooUnmatched).toHaveBeenCalledTimes(1)
    expect(wrapper.find('#pane-import .yahoo-detail').text()).toContain('M-D8T-SR5')
  })

  // 取失败只影响这一块：就地报错，且下次展开必须能重试（失败不写缓存）
  it('reports the failure in place and retries on the next expand', async () => {
    apiMocks.fetchYahooBatches.mockResolvedValue([batch({ id: 11, unmatchedCount: 1 })])
    apiMocks.fetchYahooUnmatched
      .mockRejectedValueOnce(new ApiError(0, 'network down'))
      .mockResolvedValueOnce(unmatchedOf(11, [unmatchedRow()]))
    const { wrapper } = await mountView()

    await expand(wrapper)

    const detail = wrapper.find('#pane-import .yahoo-detail')
    expect(detail.find('.kcgl-error-box').text()).toContain('network down')

    await expand(wrapper)
    await expand(wrapper)

    expect(apiMocks.fetchYahooUnmatched).toHaveBeenCalledTimes(2)
    expect(wrapper.find('#pane-import .yahoo-detail').text()).toContain('M-D8T-SR5')
  })

  // 报告列里的计数是"当时"的（unmatched_count），清单是"此刻"的（item_id IS NULL）：
  // 同一拍卖被后续导入认领后，旧批次的计数仍在、清单已空。此时若还挂"下記に…"，
  // 就是指向一段不存在的清单——必须换成明说"当前没有"。
  it('explains the empty list when the report count is stale', async () => {
    apiMocks.fetchYahooBatches.mockResolvedValue([batch({ id: 12, unmatchedCount: 1 })])
    apiMocks.fetchYahooUnmatched.mockResolvedValue(unmatchedOf(12, []))
    const { wrapper } = await mountView()

    await expand(wrapper)

    expect(apiMocks.fetchYahooUnmatched).toHaveBeenCalledWith(12)
    const detail = wrapper.find('#pane-import .yahoo-detail')
    expect(detail.find('.yahoo-unmatched-total').text()).toBe('0 件')
    expect(detail.text()).toContain('現在ありません')
    expect(detail.text()).not.toContain('下記')
  })
})
