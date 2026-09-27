import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  fetchItemsForPrint: vi.fn(),
  fetchVenues: vi.fn(),
  fetchPriceBands: vi.fn(),
}))

// ApiError/errors/format 保持真实实现；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchItemsForPrint: apiMocks.fetchItemsForPrint,
    fetchVenues: apiMocks.fetchVenues,
    fetchPriceBands: apiMocks.fetchPriceBands,
  }
})

// jsdom 无 canvas：QR 生成桩为固定 dataURL；真实解码断言在 E2E（jsQR）
vi.mock('qrcode', () => ({
  default: { toDataURL: vi.fn(async () => 'data:image/png;base64,QR') },
}))

import PrintView from './PrintView.vue'
import { useDictsStore } from '@/stores/dicts'
import { i18n } from '@/i18n'
import { ApiError, type ItemSummary } from '@/utils/api'
import { JST_TZ, dayjs } from '@/utils/format'

/**
 * 标签打印页（M2-7）：默认今日 JST 自动加载 → 标签/页数渲染 →
 * 预置切换与缩略图联动 → 空态/错误/截断。
 */

function todayJst(): string {
  return dayjs().tz(JST_TZ).format('YYYY-MM-DD')
}

function row(id: number, thumbUrl: string | null = null): ItemSummary {
  return {
    id,
    itemCode: `HTK9-A${id}X`,
    buyDate: '2026-09-15',
    venueCode: 'HT',
    thumbUrl,
  }
}

function listResult(rows: ItemSummary[], total = rows.length) {
  return { total, page: 1, size: 100, rows }
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  i18n.global.locale.value = 'ja-JP'
  useDictsStore().$patch({
    venues: [{ id: 7, code: 'HT', name: '飛騨古民具市', enabled: true }],
    bands: [],
    loaded: true,
  })
})

enableAutoUnmount(afterEach)

function mountView(): VueWrapper<InstanceType<typeof PrintView>> {
  return mount(PrintView, { global: { plugins: [i18n] } })
}

describe('打印页加载与渲染', () => {
  it('默认今日 JST 区间自动加载：标签 2 件 1 页，人读码分段 + QR + 计数', async () => {
    apiMocks.fetchItemsForPrint.mockResolvedValue(
      listResult([row(1), row(2, '/img/thumb/2026/09/a_t.jpg')]),
    )
    const wrapper = mountView()
    await flushPromises()

    expect(apiMocks.fetchItemsForPrint).toHaveBeenCalledWith({
      createdFrom: todayJst(),
      createdTo: todayJst(),
      venueId: undefined,
      page: 1,
      size: 100,
    })
    expect(wrapper.findAll('.print-label')).toHaveLength(2)
    expect(wrapper.findAll('.print-sheet')).toHaveLength(1)
    // 人读码：会场/日期段带连字符，流水段独立（QR 破损手输兜底）
    expect(wrapper.find('.print-code-head').text()).toBe('HTK9-')
    expect(wrapper.find('.print-code-tail').text()).toBe('A1X')
    expect(wrapper.find('.print-date').text()).toBe('2026/09/15')
    expect(wrapper.find('.print-qr').attributes('src')).toBe('data:image/png;base64,QR')
    // 缩略图默认关闭（默认 38×21 无缩略图位）
    expect(wrapper.find('.print-thumb').exists()).toBe(false)
    expect(wrapper.find('.print-count').text()).toContain('2件・1枚')
    // 引导条：缩放/边距/页眉页脚三要点
    expect(wrapper.find('.print-guide').text()).toContain('倍率100%')
  })

  it('超单页容量跨页渲染（66 件 38×21 → 2 页）', async () => {
    apiMocks.fetchItemsForPrint.mockResolvedValue(
      listResult(Array.from({ length: 66 }, (_, i) => row(i + 1))),
    )
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.findAll('.print-label')).toHaveLength(66)
    expect(wrapper.findAll('.print-sheet')).toHaveLength(2)
    expect(wrapper.find('.print-count').text()).toContain('66件・2枚')
  })
})

describe('预置与缩略图联动', () => {
  it('38×21 下缩略图开关禁用；切到 50×30 后可开且仅带图件渲染缩略图', async () => {
    apiMocks.fetchItemsForPrint.mockResolvedValue(
      listResult([row(1), row(2, '/img/thumb/2026/09/a_t.jpg')]),
    )
    const wrapper = mountView()
    await flushPromises()

    // 默认 small：开关禁用
    expect(wrapper.find('.el-switch').classes()).toContain('is-disabled')

    // 切到 medium（50×30）：开关可用并打开
    const mediumRadio = wrapper
      .findAll('input[type="radio"]')
      .find((input) => (input.element as HTMLInputElement).value === 'medium')
    await mediumRadio!.setValue(true)
    expect(wrapper.find('.el-switch').classes()).not.toContain('is-disabled')
    await wrapper.find('.el-switch').trigger('click')
    await flushPromises()

    const thumbs = wrapper.findAll('.print-thumb')
    expect(thumbs).toHaveLength(1)
    expect(thumbs[0]!.attributes('src')).toBe('/img/thumb/2026/09/a_t.jpg')
  })
})

describe('空态与错误', () => {
  it('零结果：空态文案 + 印刷按钮禁用', async () => {
    apiMocks.fetchItemsForPrint.mockResolvedValue(listResult([]))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.print-empty').text()).toContain('該当する商品がありません')
    const printButton = wrapper.findAll('button').find((b) => b.text() === '印刷')
    expect(printButton).toBeDefined()
    expect(printButton!.attributes('disabled')).toBeDefined()
  })

  it('加载失败：错误文案 + errorId 同屏', async () => {
    apiMocks.fetchItemsForPrint.mockRejectedValue(
      new ApiError(500000, 'サーバーエラーが発生しました', 'err-7777'),
    )
    const wrapper = mountView()
    await flushPromises()

    const error = wrapper.find('.print-error')
    expect(error.text()).toContain('システムエラーが発生しました')
    expect(error.text()).toContain('ID: err-7777')
  })
})

describe('分页续拉与截断', () => {
  it('total 超上限时逐页续拉，上限截断并提示缩小区间', async () => {
    // 第 1 页 100 件、total 600；第 2 页起空（并发删除安全阀）→ 100 件 + 截断提示
    apiMocks.fetchItemsForPrint
      .mockResolvedValueOnce(listResult(Array.from({ length: 100 }, (_, i) => row(i + 1)), 600))
      .mockResolvedValueOnce(listResult([], 600))
    const wrapper = mountView()
    await flushPromises()

    expect(apiMocks.fetchItemsForPrint).toHaveBeenCalledTimes(2)
    expect(apiMocks.fetchItemsForPrint).toHaveBeenLastCalledWith({
      createdFrom: todayJst(),
      createdTo: todayJst(),
      venueId: undefined,
      page: 2,
      size: 100,
    })
    expect(wrapper.findAll('.print-label')).toHaveLength(100)
    expect(wrapper.find('.print-count').text()).toContain('100件・2枚')
    expect(wrapper.find('.print-count').text()).toContain('先頭500件のみ')
  })
})
