import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'

const apiMocks = vi.hoisted(() => ({
  adjustItem: vi.fn(),
}))

// ApiError 保持真实实现（toDisplayMessage 分支依赖 instanceof+code）；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return { ...actual, adjustItem: apiMocks.adjustItem }
})

import ItemAdjustDialog from './ItemAdjustDialog.vue'
import { i18n } from '@/i18n'
import { ApiError } from '@/utils/api'
import type { ItemResponse } from '@/utils/api'

/**
 * 手工修正弹层（D4，A-only）：任意态覆盖两轴、只提交被指定的轴、reason 必填、
 * 无变化拦住不写流水、失败内联报错且不关弹层。
 *
 * 状态下拉用组件模型的 update:modelValue 驱动而非点开 popper——el-select 的
 * 下拉是 teleport 到 body 的，jsdom 里既取不到也不该由本组件的测试去覆盖
 * Element Plus 自己的交互（本仓库既有 spec 同样只驱动到组件契约这一层）。
 */

function item(overrides: Partial<ItemResponse> = {}): ItemResponse {
  return {
    id: 7,
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
    stockStatus: 1,
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

/** 用组件契约设定第 index 个下拉（0=库存态 1=销售态）：按根元素 .el-select 定位组件。 */
function pickAxis(wrapper: VueWrapper, index: number, value: number): void {
  const selects = wrapper.findAllComponents('.el-select')
  expect(selects).toHaveLength(2)
  selects[index]!.vm.$emit('update:modelValue', value)
}

/**
 * 开弹层必须走「先关后开」这一次转变——el-dialog 只在 modelValue 由假转真时
 * 才把内容渲染出来，直接以 true 挂载会得到一个空弹层（生产路径也是点按钮才开）。
 */
async function mountDialog(data: Partial<ItemResponse> = {}) {
  const wrapper = mount(ItemAdjustDialog, {
    props: {
      item: item(data),
      modelValue: false,
      'onUpdate:modelValue': (next: boolean) => {
        void wrapper.setProps({ modelValue: next })
      },
    },
    global: { plugins: [i18n] },
  })
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
  return wrapper
}

function submitButton(wrapper: VueWrapper) {
  const target = wrapper.findAll('.el-dialog button').find((b) => b.text() === '修正する')
  expect(target, 'submit button should exist').toBeTruthy()
  return target!
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  vi.resetAllMocks()
  i18n.global.locale.value = 'ja-JP'
  apiMocks.adjustItem.mockResolvedValue(item())
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('item adjust dialog (D4)', () => {
  it('shows both axes with their current state and defaults to leaving them unchanged', async () => {
    const wrapper = await mountDialog({ stockStatus: 1, saleStatus: 2 })

    // 现态必须摆在眼前：管理员是来纠错的，得先看到「现在是什么」
    expect(wrapper.find('.el-dialog').text()).toContain('現在：在庫')
    expect(wrapper.find('.el-dialog').text()).toContain('現在：落札済み')
    // 两轴默认「変更しない」= 不提交任何轴
    const selects = wrapper.findAllComponents('.el-select')
    expect(selects[0]!.props('modelValue')).toBe(-1)
    expect(selects[1]!.props('modelValue')).toBe(-1)
  })

  it('requires a reason before calling the endpoint', async () => {
    const wrapper = await mountDialog()
    pickAxis(wrapper, 0, 2)
    await submitButton(wrapper).trigger('click')
    await flushPromises()

    expect(wrapper.find('.kcgl-error-box').text()).toBe('理由を入力してください。')
    expect(apiMocks.adjustItem).not.toHaveBeenCalled()
  })

  it('sends only the axis that was picked (the other key must be absent)', async () => {
    const wrapper = await mountDialog({ stockStatus: 1, saleStatus: 0 })
    pickAxis(wrapper, 0, 2)
    await wrapper.find('.el-dialog textarea').setValue('  棚卸差異の是正  ')
    await submitButton(wrapper).trigger('click')
    await flushPromises()

    expect(apiMocks.adjustItem).toHaveBeenCalledTimes(1)
    const [id, payload] = apiMocks.adjustItem.mock.calls[0]! as [number, Record<string, unknown>]
    expect(id).toBe(7)
    expect(payload.reason).toBe('棚卸差異の是正') // 前后空白已修剪
    expect(payload.stockStatus).toBe(2)
    // 键缺失=保持不变；补一个 saleStatus 会让服务端以为「改成空值」
    expect('saleStatus' in payload).toBe(false)
    expect(typeof payload.clientReqId).toBe('string')
    expect((payload.clientReqId as string).length).toBeGreaterThan(0)
  })

  it('sends both axes when both are corrected', async () => {
    const wrapper = await mountDialog({ stockStatus: 1, saleStatus: 0 })
    pickAxis(wrapper, 0, 1)
    pickAxis(wrapper, 1, 3)
    await wrapper.find('.el-dialog textarea').setValue('誤登録')
    await submitButton(wrapper).trigger('click')
    await flushPromises()

    const [, payload] = apiMocks.adjustItem.mock.calls[0]! as [number, Record<string, unknown>]
    expect(payload.stockStatus).toBe(1)
    expect(payload.saleStatus).toBe(3)
  })

  it('blocks a selection that equals the current state (no change to correct)', async () => {
    const wrapper = await mountDialog({ stockStatus: 1, saleStatus: 0 })
    pickAxis(wrapper, 0, 1) // 与现态相同
    await wrapper.find('.el-dialog textarea').setValue('意味のない修正')
    await submitButton(wrapper).trigger('click')
    await flushPromises()

    expect(wrapper.find('.kcgl-error-box').text())
      .toBe('選んだ状態が今と同じです。在庫状態か販売状態を、今と違う内容にしてください。')
    expect(apiMocks.adjustItem).not.toHaveBeenCalled()
  })

  it('emits adjusted and closes on success', async () => {
    const wrapper = await mountDialog({ stockStatus: 0, saleStatus: 0 })
    pickAxis(wrapper, 0, 2)
    await wrapper.find('.el-dialog textarea').setValue('実物は出庫済み')
    await submitButton(wrapper).trigger('click')
    await flushPromises()

    expect(wrapper.emitted('adjusted')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
  })

  it('shows the server message inline and stays open on failure', async () => {
    apiMocks.adjustItem.mockRejectedValue(new ApiError(999999, 'サーバー側で失敗しました'))
    const wrapper = await mountDialog({ stockStatus: 0, saleStatus: 0 })
    pickAxis(wrapper, 0, 2)
    await wrapper.find('.el-dialog textarea').setValue('実物は出庫済み')
    await submitButton(wrapper).trigger('click')
    await flushPromises()

    expect(wrapper.find('.kcgl-error-box').text()).toBe('サーバー側で失敗しました')
    expect(wrapper.emitted('adjusted')).toBeUndefined()
    // 失败必须留在弹层里：关掉就丢了已填的理由与选择，用户得从头再来
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })
})
