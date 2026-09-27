import 'fake-indexeddb/auto'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

const apiMocks = vi.hoisted(() => ({
  voidItem: vi.fn(),
  fetchItem: vi.fn(),
}))

// ApiError 保持真实实现（409006 分支依赖 instanceof/code 读取）；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    voidItem: apiMocks.voidItem,
    fetchItem: apiMocks.fetchItem,
  }
})

import EntrySuccess from './EntrySuccess.vue'
import { i18n } from '@/i18n'
import { ApiError, type ItemResponse } from '@/utils/api'

/**
 * 取り消して再登録（M2-6，docs/01 7.1）：理由必填 → voidItem → emit('reEntry')。
 * 409006=超时重放窗口内已作废成功——读回后照常转重录（防「作废成功但界面报错」）。
 */

const itemFixture = {
  id: 11,
  itemCode: 'HTK9-A1X',
  venueId: 7,
  venueCode: 'HT',
  year: 2026,
  yearCode: 'K',
  buyMonth: 9,
  seqPrefix: 'A',
  seqNo: 1,
  buyDate: '2026-09-27',
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
  stockStatus: 0,
  saleStatus: 0,
  voided: false,
  voidReason: null,
  reEntryOf: null,
  deleted: false,
  createdAt: '2026-09-27T10:00:00',
} as ItemResponse

const voidedFixture = { ...itemFixture, voided: true, voidReason: '価格入力ミス' }

function mountSuccess(item: ItemResponse = itemFixture): VueWrapper {
  return mount(EntrySuccess, {
    global: { plugins: [i18n] },
    props: { item, todayCount: 3, photoCount: 0 },
  })
}

/** 打开弹层并填写理由。 */
async function openAndFillReason(wrapper: VueWrapper, reason: string): Promise<void> {
  await wrapper.find('.entry-success-void').trigger('click')
  await flushPromises()
  await wrapper.find('textarea').setValue(reason)
}

async function clickConfirm(wrapper: VueWrapper): Promise<void> {
  const confirm = wrapper
    .findAll('button')
    .find((button) => button.text().includes('取り消して再入力へ'))
  await confirm!.trigger('click')
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  i18n.global.locale.value = 'ja-JP'
})

describe('void and re-entry (M2-6)', () => {
  it('requires a reason: confirming with an empty reason shows an inline error and sends no request', async () => {
    const wrapper = mountSuccess()
    await openAndFillReason(wrapper, '')
    await clickConfirm(wrapper)

    expect(wrapper.text()).toContain('取り消し理由を入力してください')
    expect(apiMocks.voidItem).not.toHaveBeenCalled()
  })

  it('calls voidItem with the filled reason and emits reEntry with the voided item', async () => {
    apiMocks.voidItem.mockResolvedValue(voidedFixture)
    const wrapper = mountSuccess()
    await openAndFillReason(wrapper, '価格入力ミス')
    await clickConfirm(wrapper)

    expect(apiMocks.voidItem).toHaveBeenCalledTimes(1)
    const [id, , reason] = apiMocks.voidItem.mock.calls[0] as unknown as [number, string, string]
    expect(id).toBe(11)
    expect(reason).toBe('価格入力ミス')

    const reEntry = wrapper.emitted('reEntry')
    expect(reEntry).toHaveLength(1)
    expect(reEntry![0]![0]).toMatchObject({ id: 11, voided: true })
  })

  it('on 409006 (retry after a lost void response): reads back via fetchItem and proceeds to re-entry as usual', async () => {
    apiMocks.voidItem.mockRejectedValue(new ApiError(409006, 'この商品は既に取り消されています'))
    apiMocks.fetchItem.mockResolvedValue(voidedFixture)
    const wrapper = mountSuccess()
    await openAndFillReason(wrapper, '価格入力ミス')
    await clickConfirm(wrapper)

    expect(apiMocks.fetchItem).toHaveBeenCalledWith(11)
    expect(wrapper.emitted('reEntry')).toHaveLength(1)
    // 不把 409006 当错误显示（作废实际已成功，报错会误导用户以为需再操作）
    expect(wrapper.text()).not.toContain('既に取り消されています')
  })

  it('shows the error message and errorId for other errors, keeping the dialog retryable', async () => {
    apiMocks.voidItem.mockRejectedValue(new ApiError(500000, 'システムエラー', 'err-c3d4'))
    const wrapper = mountSuccess()
    await openAndFillReason(wrapper, '価格入力ミス')
    await clickConfirm(wrapper)

    expect(wrapper.text()).toContain('ID: err-c3d4')
    expect(wrapper.emitted('reEntry')).toBeUndefined()
    expect(wrapper.find('.entry-void').exists()).toBe(true)
  })

  it('cancel button closes the dialog without sending a request', async () => {
    const wrapper = mountSuccess()
    await openAndFillReason(wrapper, '価格入力ミス')

    const cancel = wrapper
      .findAll('button')
      .find((button) => button.text().includes('キャンセル'))
    await cancel!.trigger('click')
    await flushPromises()

    expect(apiMocks.voidItem).not.toHaveBeenCalled()
    expect(wrapper.emitted('reEntry')).toBeUndefined()
  })
})
