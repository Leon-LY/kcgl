import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'

const apiMocks = vi.hoisted(() => ({
  fetchYearCodes: vi.fn(),
  createYearCode: vi.fn(),
  updateYearCode: vi.fn(),
}))

// ApiError 保持真实实现；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchYearCodes: apiMocks.fetchYearCodes,
    createYearCode: apiMocks.createYearCode,
    updateYearCode: apiMocks.updateYearCode,
  }
})

import YearCodeAdminView from './YearCodeAdminView.vue'
import { i18n } from '@/i18n'
import { ApiError } from '@/utils/api'
import type { YearCode } from '@/utils/api'

/**
 * 年代号管理页（M2-8b-2）：列表/新增（年份 2016-2999+单字母校验）/
 * 编辑预填/服务端 409 就地展示。仅管理员可达（路由守卫+服务端 403）。
 */

function yearCode(id: number, year: number, code: string): YearCode {
  return { id, year, code }
}

const SEED: YearCode[] = [
  yearCode(31, 2026, 'K'),
  yearCode(32, 2027, 'L'),
]

function rows(wrapper: VueWrapper) {
  return wrapper.findAll('.el-table__row')
}

async function mountView(): Promise<VueWrapper> {
  const wrapper = mount(YearCodeAdminView, {
    global: { plugins: [i18n] },
  })
  await flushPromises()
  return wrapper
}

async function fillDialog(wrapper: VueWrapper, year: string, code: string): Promise<void> {
  await wrapper.find('.admin-header button').trigger('click')
  await flushPromises()
  const inputs = wrapper.findAll('.el-dialog input')
  await inputs[0]!.setValue(year)
  await inputs[1]!.setValue(code)
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

describe('year code admin (M2-8b-2)', () => {
  it('renders the seeded year codes', async () => {
    apiMocks.fetchYearCodes.mockResolvedValue(SEED)
    const wrapper = await mountView()

    expect(rows(wrapper)).toHaveLength(2)
    expect(rows(wrapper)[0]!.text()).toContain('2026')
    expect(rows(wrapper)[0]!.text()).toContain('K')
  })

  it('creates a year code after rejecting an out-of-range year and a bad code', async () => {
    apiMocks.fetchYearCodes.mockResolvedValue(SEED)
    const wrapper = await mountView()

    // 年份越界 → 就地报错
    await fillDialog(wrapper, '1999', 'M')
    await submit(wrapper)
    expect(wrapper.find('.admin-form-error').text()).toContain('2016〜2999')
    expect(apiMocks.createYearCode).not.toHaveBeenCalled()

    // 非法代号 → 就地报错
    const inputs = wrapper.findAll('.el-dialog input')
    await inputs[0]!.setValue('2042')
    await inputs[1]!.setValue('m')
    await submit(wrapper)
    expect(wrapper.find('.admin-form-error').text()).toContain('年代号は半角英字1桁')
    expect(apiMocks.createYearCode).not.toHaveBeenCalled()

    // 合法 → 提交并重载
    await inputs[1]!.setValue('M')
    apiMocks.createYearCode.mockResolvedValue(yearCode(33, 2042, 'M'))
    apiMocks.fetchYearCodes.mockResolvedValue([...SEED, yearCode(33, 2042, 'M')])
    await submit(wrapper)

    expect(apiMocks.createYearCode).toHaveBeenCalledWith({ year: 2042, code: 'M' })
    expect(rows(wrapper)).toHaveLength(3)
  })

  it('edits a year code with prefilled fields', async () => {
    apiMocks.fetchYearCodes.mockResolvedValue(SEED)
    const wrapper = await mountView()

    await rows(wrapper)[1]!.findAll('button').filter((b) => b.text() === '編集')[0]!.trigger('click')
    await flushPromises()

    const inputs = wrapper.findAll('.el-dialog input')
    expect((inputs[0]!.element as HTMLInputElement).value).toBe('2027')
    expect((inputs[1]!.element as HTMLInputElement).value).toBe('L')

    await inputs[1]!.setValue('M')
    apiMocks.updateYearCode.mockResolvedValue(yearCode(32, 2027, 'M'))
    await submit(wrapper)

    expect(apiMocks.updateYearCode).toHaveBeenCalledWith(32, { year: 2027, code: 'M' })
  })

  it('shows the duplicate error from the server and a retry for list failures', async () => {
    apiMocks.fetchYearCodes.mockRejectedValue(new ApiError(0, 'NETWORK_ERROR'))
    const wrapper = await mountView()
    expect(wrapper.find('.admin-error').text()).toContain('読み込みに失敗しました')

    apiMocks.fetchYearCodes.mockResolvedValue(SEED)
    await wrapper.find('.admin-error button').trigger('click')
    await flushPromises()
    expect(rows(wrapper)).toHaveLength(2)

    // 服务端 409005（年或代号已存在）→ 弹层内展示不关弹
    await fillDialog(wrapper, '2042', 'K')
    apiMocks.createYearCode.mockRejectedValue(
      new ApiError(409005, 'この年または年代号は既に登録されています'),
    )
    await submit(wrapper)
    expect(wrapper.find('.admin-form-error').text()).toContain('既に登録されています')
    expect(wrapper.find('.el-dialog').exists()).toBe(true)
  })
})
