import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

const apiMocks = vi.hoisted(() => ({
  fetchExcelBatches: vi.fn(),
  uploadExcelWorkbook: vi.fn(),
  downloadExcelTemplate: vi.fn(),
  downloadExcelExport: vi.fn(),
}))

// ApiError 保持真实实现（错误文案分支依赖 instanceof/code）；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchExcelBatches: apiMocks.fetchExcelBatches,
    uploadExcelWorkbook: apiMocks.uploadExcelWorkbook,
    downloadExcelTemplate: apiMocks.downloadExcelTemplate,
    downloadExcelExport: apiMocks.downloadExcelExport,
  }
})

import ExcelView from './ExcelView.vue'
import { i18n } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/utils/api'
import type { ExcelImportBatch, MeResponse } from '@/utils/api'

/**
 * エクセル連携桌面页（M4-⑤，D-058）：批次历史（状态/双模式计数/採番メモ）/
 * viewer 禁模板禁上传/上传 FormData 流程与 409012 就地文案/处理中轮询起停/
 * 模板下载走 blob 保存/导出（码全角归一化+単票抽出优先+无码倒挂区间就地拦截）。
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

function batch(overrides: Partial<ExcelImportBatch> = {}): ExcelImportBatch {
  return {
    id: 1,
    originalFilename: 'items.xlsx',
    status: 1,
    rowCount: 2,
    generatedCount: 1,
    importedCount: 1,
    errorCount: 0,
    note: null,
    errorMessage: null,
    uploadedBy: 2,
    createdAt: '2026-09-28 09:00:00',
    finishedAt: '2026-09-28 09:00:02',
    errorRows: [],
    ...overrides,
  }
}

async function mountView(role: 1 | 3 = 1, tab?: string): Promise<VueWrapper & { router: Router }> {
  const auth = useAuthStore()
  auth.me = role === 1 ? meAdmin : meViewer
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/excel', name: 'excel', component: { template: '<div />' } },
      { path: '/items', name: 'items', component: { template: '<div />' } },
    ],
  })
  await router.push({ name: 'excel', query: tab === undefined ? {} : { tab } })
  const wrapper = mount(ExcelView, {
    global: { plugins: [i18n, router] },
  })
  await flushPromises()
  return Object.assign(wrapper, { router })
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

/** 切到エクスポート标签（两个 pane 默认都渲染，交互前先激活保证可见语义）。 */
async function openExportTab(wrapper: VueWrapper): Promise<void> {
  await wrapper.find('#tab-export').trigger('click')
  await flushPromises()
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  vi.resetAllMocks()
  // setup.ts 的 blob URL 桩会被 resetAllMocks 清掉实现——重建为确定性桩
  URL.createObjectURL = vi.fn(() => 'blob:excel-mock')
  URL.revokeObjectURL = vi.fn()
  setActivePinia(createPinia())
  i18n.global.locale.value = 'ja-JP'
  apiMocks.fetchExcelBatches.mockResolvedValue([])
  apiMocks.downloadExcelTemplate.mockResolvedValue({
    blob: new Blob(['PK']),
    filename: '商品登録テンプレート.xlsx',
  })
  apiMocks.downloadExcelExport.mockResolvedValue({
    blob: new Blob(['PK']),
    filename: '商品一覧.xlsx',
  })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('excel view (M4-5)', () => {
  it('renders batch history with status tags, dual-mode counts and jump note', async () => {
    apiMocks.fetchExcelBatches.mockResolvedValue([
      batch({ note: 'A0→A5' }),
      batch({ id: 2, status: 0, rowCount: 0, generatedCount: 0, importedCount: 0,
        errorCount: 0, finishedAt: null }),
      batch({ id: 3, status: 2, errorMessage: '1列目の表頭が一致しません',
        rowCount: 0, generatedCount: 0, importedCount: 0, errorCount: 0, finishedAt: null,
        errorRows: [{ line: 3, raw: 'HT9-A5X,HT,…', reason: '倉庫の値が不正です' }] }),
    ])
    const wrapper = await mountView()

    const rows = wrapper.findAll('#pane-import .el-table__row')
    expect(rows).toHaveLength(3)
    expect(wrapper.text()).toContain('items.xlsx')
    const tags = wrapper.findAll('#pane-import .excel-tag')
    expect(tags.map((tag) => tag.text())).toEqual(['完了', '処理中', '失敗'])
    // 完成批次：双模式计数真实值 + 採番メモ列
    expect(rows[0]!.text()).toContain('A0→A5')
    // 失败批次展开：错误行采样表
    await rows[2]!.find('.el-table__expand-icon').trigger('click')
    await flushPromises()
    const detail = wrapper.find('.excel-detail')
    expect(detail.text()).toContain('倉庫の値が不正です')
    expect(detail.text()).toContain('1列目の表頭が一致しません')
  })

  it('viewer cannot download template nor upload and sees the role note', async () => {
    const wrapper = await mountView(3)

    const buttons = wrapper.findAll('.excel-upload-row button')
    expect(buttons).toHaveLength(2)
    for (const button of buttons) {
      expect(button.attributes('disabled')).toBeDefined()
    }
    expect(wrapper.find('.excel-upload .kcgl-info-box').text()).toBe(
      'インポートには編集者以上の権限が必要です。',
    )
  })

  it('uploads the chosen workbook as FormData and refreshes the history', async () => {
    const wrapper = await mountView()
    expect(apiMocks.fetchExcelBatches).toHaveBeenCalledTimes(1)

    apiMocks.uploadExcelWorkbook.mockResolvedValue(batch({ status: 0 }))
    const input = wrapper.find('.excel-upload-input')
    const file = new File(['PK'], 'items.xlsx', {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    })
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    await flushPromises()

    expect(apiMocks.uploadExcelWorkbook).toHaveBeenCalledTimes(1)
    const form = apiMocks.uploadExcelWorkbook.mock.calls[0]![0] as FormData
    expect(form.get('file')).toBe(file)
    expect(apiMocks.fetchExcelBatches).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.excel-upload .kcgl-error-box').exists()).toBe(false)
  })

  it('upload failure (sha duplicate) shows the mapped message in place', async () => {
    const wrapper = await mountView()

    apiMocks.uploadExcelWorkbook.mockRejectedValue(new ApiError(409012, 'duplicate'))
    const input = wrapper.find('.excel-upload-input')
    const file = new File(['PK'], 'items.xlsx')
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    await flushPromises()

    expect(wrapper.find('.excel-upload .kcgl-error-box').text()).toBe(
      i18n.global.t('errors.409012'),
    )
  })

  it('starts polling while a batch is processing and stops at terminal state', async () => {
    const setIntervalSpy = vi.spyOn(window, 'setInterval')
    const clearIntervalSpy = vi.spyOn(window, 'clearInterval')
    apiMocks.fetchExcelBatches
      .mockResolvedValueOnce([batch({ status: 0 })])
      .mockResolvedValueOnce([batch({ status: 1 })])
    const wrapper = await mountView()

    expect(setIntervalSpy).toHaveBeenCalledWith(expect.any(Function), 2000)

    // 手动触发一次轮询回调：批次到终态后应停止
    const tick = setIntervalSpy.mock.calls[0]![0] as () => void
    tick()
    await flushPromises()

    expect(apiMocks.fetchExcelBatches).toHaveBeenCalledTimes(2)
    expect(clearIntervalSpy).toHaveBeenCalled()
    expect(wrapper.find('#pane-import .excel-tag').text()).toBe('完了')
  })

  it('downloads the template via blob URL and clears the busy state', async () => {
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click')
    const wrapper = await mountView()

    await wrapper.findAll('.excel-upload-row button')[0]!.trigger('click')
    await flushPromises()

    expect(apiMocks.downloadExcelTemplate).toHaveBeenCalledTimes(1)
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1)
    expect(clickSpy).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.excel-upload .kcgl-error-box').exists()).toBe(false)
    // 失败路径：错误就地展示，不落文件
    apiMocks.downloadExcelTemplate.mockRejectedValueOnce(new ApiError(0, 'network down'))
    await wrapper.findAll('.excel-upload-row button')[0]!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.excel-upload .kcgl-error-box').text()).toContain('network down')
  })

  it('exports with normalized code taking precedence over an inverted range', async () => {
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click')
    const wrapper = await mountView()
    await openExportTab(wrapper)

    // 全角手输（IME 场景）：NFKC 归一 + 大写化后作为単票抽出条件；倒挂区间不校验
    wrapper.findComponent({ name: 'ElDatePicker' }).vm.$emit(
      'update:modelValue',
      ['2026-12-31', '2026-01-01'],
    )
    await wrapper.find('.excel-code input').setValue('ｈｔ９－ａ１ｘ')
    await wrapper.find('.excel-export-row .el-button').trigger('click')
    await flushPromises()

    expect(apiMocks.downloadExcelExport).toHaveBeenCalledWith({
      createdFrom: '2026-12-31',
      createdTo: '2026-01-01',
      venueId: null,
      code: 'HT9-A1X',
    })
    expect(clickSpy).toHaveBeenCalledTimes(1)

    // 会场筛选透传（下拉 emit 直驱）
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 3)
    await wrapper.find('.excel-export-row .el-button').trigger('click')
    await flushPromises()
    expect(apiMocks.downloadExcelExport).toHaveBeenLastCalledWith({
      createdFrom: '2026-12-31',
      createdTo: '2026-01-01',
      venueId: 3,
      code: 'HT9-A1X',
    })
  })

  it('export without code rejects an inverted range in place before any request', async () => {
    const wrapper = await mountView()
    await openExportTab(wrapper)

    wrapper.findComponent({ name: 'ElDatePicker' }).vm.$emit(
      'update:modelValue',
      ['2026-12-31', '2026-01-01'],
    )
    await wrapper.find('.excel-code input').setValue('')
    await wrapper.find('.excel-export-row .el-button').trigger('click')
    await flushPromises()

    expect(apiMocks.downloadExcelExport).not.toHaveBeenCalled()
    expect(wrapper.find('#pane-export .kcgl-error-box').text()).toBe(
      i18n.global.t('excel.export.rangeInverted'),
    )

    // 请求失败（如后端 400）：错误就地展示
    apiMocks.downloadExcelExport.mockRejectedValueOnce(new ApiError(0, 'boom'))
    wrapper.findComponent({ name: 'ElDatePicker' }).vm.$emit(
      'update:modelValue',
      ['2026-01-01', '2026-12-31'],
    )
    await wrapper.find('.excel-export-row .el-button').trigger('click')
    await flushPromises()
    expect(wrapper.find('#pane-export .kcgl-error-box').text()).toContain('boom')
  })

  it('shows load errors with a reload action', async () => {
    apiMocks.fetchExcelBatches
      .mockRejectedValueOnce(new ApiError(0, 'network down'))
      .mockResolvedValueOnce([batch()])
    const wrapper = await mountView()

    const errorBox = wrapper.find('#pane-import .kcgl-error-box')
    expect(errorBox.text()).toContain('network down')

    await errorBox.find('button').trigger('click')
    await flushPromises()

    expect(apiMocks.fetchExcelBatches).toHaveBeenCalledTimes(2)
    expect(wrapper.find('#pane-import .kcgl-error-box').exists()).toBe(false)
  })

  // 空表 = 本页最常态（还没导入过），此时必须撤遮罩。曾把遮罩条件写成数据派生
  // （batches.length === 0 && !batchesError）→ 空列表取回后条件恒真，用户看到"一直转圈"
  it('clears the loading mask once an empty batch list arrives', async () => {
    let resolveBatches: (rows: ExcelImportBatch[]) => void = () => {}
    apiMocks.fetchExcelBatches.mockReturnValue(
      new Promise<ExcelImportBatch[]>((resolve) => {
        resolveBatches = resolve
      }),
    )
    const wrapper = await mountView()

    // 请求在途：唯一该转圈的时刻
    expect(isMaskHidden(wrapper)).toBe(false)

    resolveBatches([])
    await flushPromises()

    expect(isMaskHidden(wrapper)).toBe(true)
    expect(wrapper.find('#pane-import .empty-state').exists()).toBe(true)
  })
})

/**
 * 标签页深链（D-106）：商品页工具栏的「一括入出力」落到本页对应标签，
 * 因此 `?tab=` 是对外契约，脏值必须落回默认而不是渲染出空标签。
 */
describe('excel tab deep link (D-106)', () => {
  function activeTab(wrapper: VueWrapper): string {
    return wrapper.findAll('.el-tabs__item')
      .filter((item) => item.classes().includes('is-active'))
      .map((item) => item.attributes('id') ?? '')
      .join(',')
  }

  it('defaults to the import tab without a query', async () => {
    const wrapper = await mountView()
    expect(activeTab(wrapper)).toBe('tab-import')
  })

  it('opens the export tab when ?tab=export', async () => {
    const wrapper = await mountView(1, 'export')
    expect(activeTab(wrapper)).toBe('tab-export')
  })

  it('falls back to the import tab on an unknown ?tab=', async () => {
    const wrapper = await mountView(1, 'nope')
    expect(activeTab(wrapper)).toBe('tab-import')
  })

  // 与商品页同 path、只有 query 变 → 换页键用 path（D-104）故组件不重挂，
  // 必须监听 query 才能切标签（否则第二次点「書出」看起来没反应）
  it('switches tabs when only the query changes on the same path', async () => {
    const wrapper = await mountView()
    expect(activeTab(wrapper)).toBe('tab-import')

    await wrapper.router.push({ name: 'excel', query: { tab: 'export' } })
    await flushPromises()
    expect(activeTab(wrapper)).toBe('tab-export')

    await wrapper.router.push({ name: 'excel', query: {} })
    await flushPromises()
    expect(activeTab(wrapper)).toBe('tab-import')
  })
})
