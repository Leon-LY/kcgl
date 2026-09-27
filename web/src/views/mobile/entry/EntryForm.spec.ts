import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  createItem: vi.fn(),
  previewItemCode: vi.fn(),
}))

// ApiError/errors/format 保持真实实现（EntryForm 依赖 instanceof 与 t 双参签名），
// 仅替换两个网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    createItem: apiMocks.createItem,
    previewItemCode: apiMocks.previewItemCode,
  }
})

import EntryForm from './EntryForm.vue'
import { useDictsStore } from '@/stores/dicts'
import { i18n } from '@/i18n'
import { ApiError } from '@/utils/api'
import { JST_TZ, dayjs } from '@/utils/format'

function todayJst(): string {
  return dayjs().tz(JST_TZ).format('YYYY-MM-DD')
}

function yesterdayJst(): string {
  return dayjs().tz(JST_TZ).subtract(1, 'day').format('YYYY-MM-DD')
}

const venues = [
  { id: 7, code: 'HT', name: '飛騨古民具市', enabled: true },
  { id: 8, code: 'OS', name: '大阪リサイクル市', enabled: false },
]
const bands = [
  { id: 88, code: 'X', lowerBound: 0, upperBound: 3000, enabled: true },
]

const itemFixture = {
  id: 11,
  itemCode: 'HTK9-A1X',
  venueId: 7,
  warehouse: 1,
  buyDate: todayJst(),
  purchasePrice: 1000,
} as const

function mountForm(): VueWrapper<InstanceType<typeof EntryForm>> {
  return mount(EntryForm, {
    global: { plugins: [i18n] },
  })
}

/** 经 UI 选择第一个启用会场（只测真实交互路径）。 */
async function pickFirstVenue(wrapper: VueWrapper): Promise<void> {
  await wrapper.findAll('.van-field')[0]!.trigger('click')
  await wrapper.find('.van-picker__confirm').trigger('click')
  await flushPromises()
}

async function fillAndSubmit(wrapper: VueWrapper, price: string): Promise<void> {
  const inputs = wrapper.findAll('input')
  await inputs[2]!.setValue(price)
  await inputs[2]!.trigger('blur')
  await flushPromises()
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  vi.clearAllMocks()
  i18n.global.locale.value = 'ja-JP'

  const dicts = useDictsStore()
  dicts.$patch({ venues, bands, loaded: true })
})

// 防抖定时器跨用例泄漏防护：卸载组件触发 onBeforeUnmount 清理，
// 否则上一用例的 300ms 定时器会在下一用例中触发 preview 调用
enableAutoUnmount(afterEach)

describe('必填校验（验收核心页 H7）', () => {
  it('会场/单价未填 → 就地错误提示且不提交', async () => {
    const wrapper = mountForm()
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('会場を選択してください')
    expect(wrapper.text()).toContain('仕入単価を入力してください')
    expect(apiMocks.createItem).not.toHaveBeenCalled()
  })

  it('单价 0 → 低于下限错误（服务端 @Min(1) 同口径的前置拦截）', async () => {
    const wrapper = mountForm()
    await pickFirstVenue(wrapper)
    await fillAndSubmit(wrapper, '0')
    expect(wrapper.text()).toContain('仕入単価は1円以上で入力してください')
    expect(apiMocks.createItem).not.toHaveBeenCalled()
  })
})

describe('IME 全半角归一化（7.8：blur 时机）', () => {
  it('全角１０００ → blur 后半角 1000，提交为数值', async () => {
    apiMocks.createItem.mockResolvedValue(itemFixture)
    const wrapper = mountForm()
    await pickFirstVenue(wrapper)

    const inputs = wrapper.findAll('input')
    await inputs[2]!.setValue('１０００')
    await inputs[2]!.trigger('blur')
    await flushPromises()
    expect(inputs[2]!.element.value).toBe('1000')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(apiMocks.createItem).toHaveBeenCalledTimes(1)
    const payload = apiMocks.createItem.mock.calls[0]![0] as Record<string, unknown>
    expect(payload.purchasePrice).toBe(1000)
    expect(payload.venueId).toBe(7)
    expect(payload.buyDate).toBe(todayJst())
  })
})

describe('沿用上一件（A13）', () => {
  it('挂载时取会话沿用值：会场/仓库/落札日/单价 + 前日角标', async () => {
    localStorage.setItem(
      'kcgl-entry-session',
      JSON.stringify({
        venueId: 7,
        warehouse: 2,
        buyDate: yesterdayJst(),
        purchasePrice: 1500,
        todayCount: 3,
        today: todayJst(),
      }),
    )
    const wrapper = mountForm()
    const inputs = wrapper.findAll('input')

    // 会场名沿用显示（readonly 字段值为 input value，非文本节点）
    expect(inputs[0]!.element.value).toBe('飛騨古民具市')
    // 落札日沿用昨天（YYYY/MM/DD 展示）+ 前日角标（文本节点）
    expect(inputs[1]!.element.value).toBe(yesterdayJst().replaceAll('-', '/'))
    expect(wrapper.text()).toContain('前日の日付')
    // 单价沿用
    expect(inputs[2]!.element.value).toBe('1500')
    // 仓库沿用福岡
    const options = wrapper.findAll('.entry-warehouse-option')
    expect(options[1]!.classes()).toContain('is-active')
  })
})

describe('管理号两级预览（预览≠保留）', () => {
  it('本地档位即时算 + 300ms 防抖合并为一次 preview 请求', async () => {
    apiMocks.previewItemCode.mockResolvedValue({ code: 'HTK9-A2X', bandCode: 'X', seqPrefix: 'A', seqNo: 2 })
    const wrapper = mountForm()
    await pickFirstVenue(wrapper)

    const inputs = wrapper.findAll('input')
    await inputs[2]!.setValue('1000')
    await inputs[2]!.setValue('1500') // 防抖窗口内二次输入 → 合并
    await new Promise((resolve) => setTimeout(resolve, 350))

    // 档位字母来自本地缓存（零往返）
    expect(wrapper.find('.entry-band')!.text()).toContain('X')
    // 完整号来自 preview（以保存为准的目安）
    expect(apiMocks.previewItemCode).toHaveBeenCalledTimes(1)
    expect(apiMocks.previewItemCode).toHaveBeenCalledWith(7, todayJst(), 1500)
    expect(wrapper.text()).toContain('HTK9-A2X')
    expect(wrapper.text()).toContain('確定番号は保存時に発行されます')
  })

  it('preview 业务错误（如 404004）就地显示', async () => {
    apiMocks.previewItemCode.mockRejectedValue(new ApiError(404004, '年代号未登録', 'e-1'))
    const wrapper = mountForm()
    await pickFirstVenue(wrapper)

    const inputs = wrapper.findAll('input')
    await inputs[2]!.setValue('1000')
    await new Promise((resolve) => setTimeout(resolve, 350))

    expect(wrapper.text()).toContain('年代号が未登録')
  })
})

describe('保存失败重试（7.0 幂等：复用同 clientReqId）', () => {
  it('失败 → 错误框带 errorId；重试同键；成功后 emitted saved', async () => {
    apiMocks.createItem
      .mockRejectedValueOnce(new ApiError(500000, 'システムエラー', 'err-a1b2'))
      .mockResolvedValueOnce(itemFixture)
    const wrapper = mountForm()
    await pickFirstVenue(wrapper)
    await fillAndSubmit(wrapper, '1000')

    expect(wrapper.find('.kcgl-error-box').exists()).toBe(true)
    expect(wrapper.text()).toContain('ID: err-a1b2')
    expect(wrapper.text()).toContain('同じ内容で再送信')

    await wrapper.find('.entry-error-retry').trigger('click')
    await flushPromises()

    expect(apiMocks.createItem).toHaveBeenCalledTimes(2)
    const first = apiMocks.createItem.mock.calls[0]![0] as { clientReqId: string }
    const second = apiMocks.createItem.mock.calls[1]![0] as { clientReqId: string }
    expect(second.clientReqId).toBe(first.clientReqId)
    expect(first.clientReqId).toMatch(/^[0-9a-f-]{36}$/)
    expect(wrapper.emitted('saved')?.[0]?.[0]).toMatchObject({ id: 11, itemCode: 'HTK9-A1X' })
  })
})
