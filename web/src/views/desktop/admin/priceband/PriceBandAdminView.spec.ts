import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'

const apiMocks = vi.hoisted(() => ({
  fetchPriceBands: vi.fn(),
  createPriceBand: vi.fn(),
  updatePriceBand: vi.fn(),
  setPriceBandStatus: vi.fn(),
}))

// ApiError 保持真实实现；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchPriceBands: apiMocks.fetchPriceBands,
    createPriceBand: apiMocks.createPriceBand,
    updatePriceBand: apiMocks.updatePriceBand,
    setPriceBandStatus: apiMocks.setPriceBandStatus,
  }
})

import PriceBandAdminView from './PriceBandAdminView.vue'
import { i18n } from '@/i18n'
import { ApiError } from '@/utils/api'
import type { PriceBand } from '@/utils/api'

/**
 * 价格档位管理页（M2-8b-2）：区间人读展示（左闭右开→闭区间文案）/
 * 无界端留空语义/校验（码/区间）/编辑预填/停用启用/重叠 409 就地展示。
 * 页面仅管理员可达（路由守卫+服务端 403），组件内不再做角色分支。
 */

function band(id: number, code: string, lower: number | null, upper: number | null, enabled = true): PriceBand {
  return { id, code, lowerBound: lower, upperBound: upper, enabled }
}

const SEED: PriceBand[] = [
  band(21, 'W', null, 1000),
  band(22, 'X', 1000, 3000),
  band(23, 'Y', 3000, null, false),
]

function rows(wrapper: VueWrapper) {
  return wrapper.findAll('.el-table__row')
}

async function mountView(): Promise<VueWrapper> {
  const wrapper = mount(PriceBandAdminView, {
    global: { plugins: [i18n] },
  })
  await flushPromises()
  return wrapper
}

/** 打开新增弹层并填三字段（code, lower, upper；空串=无界）。 */
async function fillDialog(
  wrapper: VueWrapper,
  code: string,
  lower: string,
  upper: string,
): Promise<void> {
  await wrapper.find('.admin-header button').trigger('click')
  await flushPromises()
  const inputs = wrapper.findAll('.el-dialog input')
  await inputs[0]!.setValue(code)
  await inputs[1]!.setValue(lower)
  await inputs[2]!.setValue(upper)
}

async function submit(wrapper: VueWrapper): Promise<void> {
  await wrapper.findAll('.el-dialog button').filter((b) => b.text() === '保存')[0]!.trigger('click')
  await flushPromises()
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  vi.resetAllMocks()
  i18n.global.locale.value = 'ja-JP'
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('price band admin (M2-8b-2)', () => {
  it('renders human-readable closed ranges including unbounded ends', async () => {
    apiMocks.fetchPriceBands.mockResolvedValue(SEED)
    const wrapper = await mountView()

    expect(rows(wrapper)).toHaveLength(3)
    // [null,1000) → 〜999円；[1000,3000) → 1,000〜2,999円；[3000,null) → 3,000円以上
    expect(rows(wrapper)[0]!.text()).toContain('〜999円')
    expect(rows(wrapper)[1]!.text()).toContain('1,000〜2,999円')
    expect(rows(wrapper)[2]!.text()).toContain('3,000円以上')
    expect(rows(wrapper)[2]!.text()).toContain('停止')
  })

  it('creates a band after rejecting an inverted range and a bad code', async () => {
    apiMocks.fetchPriceBands.mockResolvedValue(SEED)
    const wrapper = await mountView()

    // 下限>=上限 → 就地报错
    await fillDialog(wrapper, 'Z', '5000', '1000')
    await submit(wrapper)
    expect(wrapper.find('.admin-form-error').text()).toContain('下限は上限より小さい値')
    expect(apiMocks.createPriceBand).not.toHaveBeenCalled()

    // 非法码 → 就地报错
    const inputs = wrapper.findAll('.el-dialog input')
    await inputs[0]!.setValue('9')
    await inputs[1]!.setValue('5000')
    await inputs[2]!.setValue('')
    await submit(wrapper)
    expect(wrapper.find('.admin-form-error').text()).toContain('価格帯コードは半角英字1桁')
    expect(apiMocks.createPriceBand).not.toHaveBeenCalled()

    // 合法（上限留空=无界）→ payload lowerBound/upperBound 语义正确
    await inputs[0]!.setValue('Z')
    apiMocks.createPriceBand.mockResolvedValue(band(24, 'Z', 5000, null))
    apiMocks.fetchPriceBands.mockResolvedValue([...SEED, band(24, 'Z', 5000, null)])
    await submit(wrapper)

    expect(apiMocks.createPriceBand).toHaveBeenCalledWith({
      code: 'Z',
      lowerBound: 5000,
      upperBound: null,
    })
    expect(rows(wrapper)).toHaveLength(4)
  })

  it('edits a band with prefilled bounds and keeps the id in the payload path', async () => {
    apiMocks.fetchPriceBands.mockResolvedValue(SEED)
    const wrapper = await mountView()

    await rows(wrapper)[1]!.findAll('button').filter((b) => b.text() === '編集')[0]!.trigger('click')
    await flushPromises()

    const inputs = wrapper.findAll('.el-dialog input')
    expect((inputs[0]!.element as HTMLInputElement).value).toBe('X')
    expect((inputs[1]!.element as HTMLInputElement).value).toBe('1000')
    expect((inputs[2]!.element as HTMLInputElement).value).toBe('3000')

    await inputs[2]!.setValue('4000')
    apiMocks.updatePriceBand.mockResolvedValue(band(22, 'X', 1000, 4000))
    await submit(wrapper)

    expect(apiMocks.updatePriceBand).toHaveBeenCalledWith(22, {
      code: 'X',
      lowerBound: 1000,
      upperBound: 4000,
    })
  })

  it('shows the overlap error from the server inside the dialog', async () => {
    apiMocks.fetchPriceBands.mockResolvedValue(SEED)
    const wrapper = await mountView()

    await fillDialog(wrapper, 'Z', '2000', '2500')
    apiMocks.createPriceBand.mockRejectedValue(
      new ApiError(409004, '価格帯の範囲が既存の価格帯と重複しています'),
    )
    await submit(wrapper)

    expect(wrapper.find('.admin-form-error').text()).toContain('重複')
    expect(wrapper.find('.el-dialog').exists()).toBe(true)
  })

  it('toggles band status and reloads', async () => {
    apiMocks.fetchPriceBands.mockResolvedValue(SEED)
    const wrapper = await mountView()

    const enableButton = rows(wrapper)[2]!.findAll('button').filter((b) => b.text() === '有効にする')[0]!
    apiMocks.setPriceBandStatus.mockResolvedValue(band(23, 'Y', 3000, null, true))
    await enableButton.trigger('click')
    await flushPromises()

    expect(apiMocks.setPriceBandStatus).toHaveBeenCalledWith(23, true)
    expect(apiMocks.fetchPriceBands).toHaveBeenCalledTimes(2)
  })
})
