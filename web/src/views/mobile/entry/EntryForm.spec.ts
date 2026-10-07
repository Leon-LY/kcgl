import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  createItem: vi.fn(),
  previewItemCode: vi.fn(),
  uploadImage: vi.fn(),
  fetchItemImages: vi.fn(),
}))

// ApiError/errors/format 保持真实实现（EntryForm 依赖 instanceof 与 t 双参签名），
// 仅替换网络端点；压缩桩为透传（jsdom 无 canvas）
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    createItem: apiMocks.createItem,
    previewItemCode: apiMocks.previewItemCode,
    uploadImage: apiMocks.uploadImage,
    fetchItemImages: apiMocks.fetchItemImages,
  }
})
vi.mock('@/utils/compress', () => ({
  compressImage: async (file: File) => ({ data: await file.arrayBuffer(), mimeType: 'image/jpeg' }),
}))

import EntryForm from './EntryForm.vue'
import { useDictsStore } from '@/stores/dicts'
import { i18n } from '@/i18n'
import { db } from '@/db/dexie'
import { useUploadQueue } from '@/composables/useUploadQueue'
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
  itemCode: 'HT9-A1X',
  venueId: 7,
  warehouse: 1,
  buyDate: todayJst(),
  purchasePrice: 1000,
} as const

function mountForm(props: Record<string, unknown> = {}): VueWrapper<InstanceType<typeof EntryForm>> {
  return mount(EntryForm, {
    global: { plugins: [i18n] },
    props,
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

/**
 * 等待断言条件成立：保存链（createItem → bindItem → emit）与队列链横跨 Dexie 宏任务，
 * flushPromises 盖不住，统一用 10ms 轮询（probe 可异步）。
 */
async function waitFor<T>(
  probe: () => T | undefined | Promise<T | undefined>,
  timeoutMs = 1000,
): Promise<T> {
  const start = Date.now()
  for (;;) {
    const value = await probe()
    if (value !== undefined) {
      return value
    }
    if (Date.now() - start > timeoutMs) {
      throw new Error('waitFor 超时')
    }
    await new Promise((resolve) => setTimeout(resolve, 10))
  }
}

beforeEach(async () => {
  setActivePinia(createPinia())
  localStorage.clear()
  vi.clearAllMocks()
  apiMocks.uploadImage.mockReset()
  apiMocks.uploadImage.mockResolvedValue({
    id: 1,
    clientUuid: 'x',
    itemId: 11,
    url: '/img/orig/2026/09/x.jpg',
    thumbUrl: '/img/thumb/2026/09/x.jpg',
    imageType: 1,
    sortOrder: 0,
  })
  // 先等上一用例可能遗留的后台泵静止（1s 兜底防卡死），再清表——
  // 否则失败用例的遗留上传链会把调用打进展新 reset 的 mock，污染下一用例
  await Promise.race([
    useUploadQueue().whenIdle(),
    new Promise((resolve) => setTimeout(resolve, 1000)),
  ])
  await useUploadQueue().resetForTests()
  i18n.global.locale.value = 'ja-JP'

  const dicts = useDictsStore()
  dicts.$patch({ venues, bands, loaded: true })
})

// 防抖定时器跨用例泄漏防护：卸载组件触发 onBeforeUnmount 清理，
// 否则上一用例的 300ms 定时器会在下一用例中触发 preview 调用
enableAutoUnmount(afterEach)

describe('required-field validation (acceptance core page H7)', () => {
  it('shows inline errors for missing venue/price and does not submit', async () => {
    const wrapper = mountForm()
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('会場を選択してください')
    expect(wrapper.text()).toContain('仕入単価を入力してください')
    expect(apiMocks.createItem).not.toHaveBeenCalled()
  })

  it('rejects a price of 0 as below the minimum (front-end guard matching the server @Min(1))', async () => {
    const wrapper = mountForm()
    await pickFirstVenue(wrapper)
    await fillAndSubmit(wrapper, '0')
    expect(wrapper.text()).toContain('仕入単価は1円以上で入力してください')
    expect(apiMocks.createItem).not.toHaveBeenCalled()
  })
})

describe('IME full/half-width normalization (7.8: on blur)', () => {
  it('normalizes full-width １０００ to 1000 on blur and submits it as a number', async () => {
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

describe('carry over from the previous item (A13)', () => {
  it('restores session carry-overs on mount: venue/warehouse/buy date/price plus the stale-date badge', async () => {
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

  it('drops a carried-over venue that has since been disabled, and says so', async () => {
    // 会场 8（OS）在夹具里是已停用会场：上次保存时可用，现在选不了了
    localStorage.setItem(
      'kcgl-entry-session',
      JSON.stringify({
        venueId: 8,
        warehouse: 2,
        buyDate: todayJst(),
        purchasePrice: 1500,
        todayCount: 3,
        today: todayJst(),
      }),
    )
    const wrapper = mountForm()
    const inputs = wrapper.findAll('input')

    // 字段清空（不是留个空壳却内部仍压着停用 id）+ 白话说明，别让用户猜为什么选过还要再选
    expect(inputs[0]!.element.value).toBe('')
    expect(wrapper.text()).toContain('前回の会場は今は使えないため')

    // 选回一个启用会场：提示消失，沿用成功
    await pickFirstVenue(wrapper)
    expect(wrapper.text()).not.toContain('前回の会場は今は使えないため')
    expect(inputs[0]!.element.value).toBe('飛騨古民具市')
  })

  it('keeps a still-enabled carried-over venue and shows no warning', async () => {
    localStorage.setItem(
      'kcgl-entry-session',
      JSON.stringify({
        venueId: 7,
        warehouse: 2,
        buyDate: todayJst(),
        purchasePrice: 1500,
        todayCount: 3,
        today: todayJst(),
      }),
    )
    const wrapper = mountForm()

    expect(wrapper.findAll('input')[0]!.element.value).toBe('飛騨古民具市')
    expect(wrapper.text()).not.toContain('前回の会場は今は使えないため')
  })
})

describe('two-level item code preview (preview ≠ reservation)', () => {
  it('computes the band locally at once and debounces input into a single preview request', async () => {
    apiMocks.previewItemCode.mockResolvedValue({ code: 'HT9-A2X', bandCode: 'X', seqPrefix: 'A', seqNo: 2 })
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
    expect(wrapper.text()).toContain('HT9-A2X')
    expect(wrapper.text()).toContain('確定番号は保存時に発行されます')
  })

  it('shows preview business errors (e.g. stale venue) inline', async () => {
    apiMocks.previewItemCode.mockRejectedValue(new ApiError(404003, '会場未登録', 'e-1'))
    const wrapper = mountForm()
    await pickFirstVenue(wrapper)

    const inputs = wrapper.findAll('input')
    await inputs[2]!.setValue('1000')
    await new Promise((resolve) => setTimeout(resolve, 350))

    expect(wrapper.text()).toContain('選択された会場が存在しません')
  })
})

describe('save-failure retry (7.0 idempotency: reuses the same clientReqId)', () => {
  it('on failure: error box with errorId; retry reuses the key; saved emitted on success', async () => {
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
    const saved = await waitFor(() => wrapper.emitted('saved')?.[0])
    expect(saved[0]).toMatchObject({ id: 11, itemCode: 'HT9-A1X' })
  })
})

// ------------------------------------------------------------------ 照片（7.5 先存后传）

const JPEG_BYTES = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10])

function fileOf(name: string): File {
  return new File([JPEG_BYTES], name, { type: 'image/jpeg' })
}

/** jsdom 无 DataTransfer：直接定义 input.files 后触发 change（EntryForm 读 target.files）。 */
async function chooseFiles(
  wrapper: VueWrapper,
  inputIndex: number,
  files: File[],
): Promise<void> {
  const input = wrapper.findAll('input[type="file"]')[inputIndex]!
  Object.defineProperty(input.element, 'files', { value: files, configurable: true })
  await input.trigger('change')
  // addFiles 链（压缩桩 + Dexie 写）横跨宏任务：等本批全部落库
  await waitFor(async () =>
    (await db.uploadQueue.count()) >= files.length ? true : undefined,
  )
  await flushPromises()
}

describe('photo selection (compress into Dexie first, bind to the new item and upload after save)', () => {
  it('camera capture → thumbnail preview, count, photo date auto-set to today, queue pending_bind', async () => {
    const wrapper = mountForm()
    await chooseFiles(wrapper, 0, [fileOf('a.jpg')]) // index 0 = capture 相机入口

    expect(wrapper.findAll('.entry-photo img')).toHaveLength(1)
    expect(wrapper.find('.entry-photo-count').text()).toBe('1/9')
    // 拍照 → 撮影日自动今天（readonly 字段值）
    expect(wrapper.find('.entry-photo-date input').element.value).toBe(todayJst().replaceAll('-', '/'))

    const rows = await db.uploadQueue.toArray()
    expect(rows).toHaveLength(1)
    expect(rows[0]!.status).toBe('pending_bind')
    expect(rows[0]!.itemId).toBeNull()
    expect(apiMocks.uploadImage).not.toHaveBeenCalled() // 未保存不上传
  })

  it('album selection does not default the photo date (EXIF reading deferred as D-034)', async () => {
    const wrapper = mountForm()
    await chooseFiles(wrapper, 1, [fileOf('a.jpg')]) // index 1 = 相册入口

    expect(wrapper.findAll('.entry-photo img')).toHaveLength(1)
    expect(wrapper.find('.entry-photo-date input').element.value).toBe('')
  })

  it('shows the inline limit message for the 10th photo and does not enqueue it', async () => {
    const wrapper = mountForm()
    const nine = Array.from({ length: 9 }, (_, i) => fileOf(`p${i}.jpg`))
    await chooseFiles(wrapper, 1, nine)
    expect(wrapper.find('.entry-photo-count').text()).toBe('9/9')

    await chooseFiles(wrapper, 1, [fileOf('over.jpg')])
    expect(wrapper.text()).toContain('写真は1件につき9枚までです')
    expect(await db.uploadQueue.count()).toBe(9)
    expect(wrapper.findAll('.entry-photo img')).toHaveLength(9)
  })

  it('× removes an unbound photo from the queue and its thumbnail', async () => {
    const wrapper = mountForm()
    await chooseFiles(wrapper, 1, [fileOf('a.jpg')])
    await wrapper.find('.entry-photo-remove').trigger('click')
    await waitFor(async () => ((await db.uploadQueue.count()) === 0 ? true : undefined))
    await flushPromises()

    expect(wrapper.findAll('.entry-photo img')).toHaveLength(0)
  })

  it('on save success: bindItem binds and starts uploading, payload carries photoDate, saved reports the photo count', async () => {
    apiMocks.createItem.mockResolvedValue(itemFixture)
    const wrapper = mountForm()
    await pickFirstVenue(wrapper)
    await chooseFiles(wrapper, 0, [fileOf('a.jpg')]) // 相机 → photoDate=今天
    await fillAndSubmit(wrapper, '1000')

    const saved = await waitFor(() => wrapper.emitted('saved')?.[0])
    const payload = apiMocks.createItem.mock.calls[0]![0] as { photoDate?: string }
    expect(payload.photoDate).toBe(todayJst())
    expect(saved).toMatchObject([
      { id: 11, itemCode: 'HT9-A1X' },
      1, // boundCount
    ])

    // 绑定后队列开始上传（mock 立即成功 → 出清）
    await useUploadQueue().whenIdle()
    expect(apiMocks.uploadImage).toHaveBeenCalledTimes(1)
    const form = apiMocks.uploadImage.mock.calls[0]![0] as FormData
    expect(form.get('itemId')).toBe('11')
    expect(await db.uploadQueue.count()).toBe(0)
  })

  it('saving without photos omits photoDate and reports photoCount=0', async () => {
    apiMocks.createItem.mockResolvedValue(itemFixture)
    const wrapper = mountForm()
    await pickFirstVenue(wrapper)
    await fillAndSubmit(wrapper, '1000')

    const saved = await waitFor(() => wrapper.emitted('saved')?.[0])
    const payload = apiMocks.createItem.mock.calls[0]![0] as { photoDate?: string }
    expect(payload.photoDate).toBeUndefined()
    expect(saved[1]).toBe(0)
    expect(apiMocks.uploadImage).not.toHaveBeenCalled()
  })
})

describe('re-entry (M2-6: full-field prefill after voiding)', () => {
  const voidedItem = {
    ...itemFixture,
    id: 31,
    itemCode: 'HT9-A3X',
    buyDate: '2026-09-20',
    purchasePrice: 1200,
    fee: 300,
    shippingFee: 200,
    tax: 10,
    shelfNo: 'S-3',
    groupNo: 'G7',
    remark: '骨董品の壺',
    photoDate: '2026-09-21',
    voided: true,
    voidReason: '価格入力ミス',
  } as const

  it('prefills all fields from the original, banners the old code, and leaves the photo date empty (new photos take a new date; otherwise the server inherits)', async () => {
    apiMocks.fetchItemImages.mockResolvedValue([])
    const wrapper = mountForm({ reEntry: voidedItem })
    await flushPromises()

    // 横幅：旧号 + 再登録文案
    expect(wrapper.text()).toContain('HT9-A3X の再登録')
    expect(wrapper.text()).toContain('新しい管理番号が発番')

    const inputs = wrapper.findAll('input')
    expect(inputs[0]!.element.value).toBe('飛騨古民具市') // venueId=7 预填
    expect(inputs[1]!.element.value).toBe('2026/09/20') // 落札日预填
    expect(inputs[2]!.element.value).toBe('1200') // 单价预填（错处就地改）
    expect(inputs[3]!.element.value).toBe('') // 撮影日不预填
    expect(wrapper.findAll('.entry-warehouse-option')[0]!.classes()).toContain('is-active')

    // 折叠字段：三费用/货架/组号/备注全部预填
    await wrapper.find('.entry-more .van-cell').trigger('click')
    await flushPromises()
    const more = wrapper.findAll('input')
    expect(more[6]!.element.value).toBe('300')
    expect(more[7]!.element.value).toBe('200')
    expect(more[8]!.element.value).toBe('10')
    expect(more[9]!.element.value).toBe('G7')
    expect(more[10]!.element.value).toBe('S-3')
    expect(wrapper.find('textarea').element.value).toBe('骨董品の壺')
  })

  it('submits with reEntryOf set to the original id (server inherits omitted fields and copies photo rows)', async () => {
    apiMocks.fetchItemImages.mockResolvedValue([])
    apiMocks.createItem.mockResolvedValue({ ...itemFixture, id: 32, itemCode: 'HT9-A4X' })
    const wrapper = mountForm({ reEntry: voidedItem })
    await flushPromises()

    await wrapper.find('form').trigger('submit')
    await waitFor(() => wrapper.emitted('saved')?.[0])

    const payload = apiMocks.createItem.mock.calls[0]![0] as Record<string, unknown>
    expect(payload.reEntryOf).toBe(31)
    expect(payload.venueId).toBe(7)
    expect(payload.purchasePrice).toBe(1200)
    expect(payload.buyDate).toBe('2026-09-20')
    expect(payload.remark).toBe('骨董品の壺')
  })

  it('shows inherited photos read-only (copied server-side, never entering local Dexie)', async () => {
    apiMocks.fetchItemImages.mockResolvedValue([
      { id: 101, clientUuid: 'a', itemId: 31, url: '/img/orig/a.jpg', thumbUrl: '/img/thumb/a.jpg', imageType: 1, sortOrder: 0 },
      { id: 102, clientUuid: 'b', itemId: 31, url: '/img/orig/b.jpg', thumbUrl: '/img/thumb/b.jpg', imageType: 1, sortOrder: 1 },
    ])
    const wrapper = mountForm({ reEntry: voidedItem })
    await flushPromises()

    expect(apiMocks.fetchItemImages).toHaveBeenCalledWith(31)
    expect(wrapper.text()).toContain('引き継ぐ画像')
    expect(wrapper.findAll('.entry-inherited-photos img')).toHaveLength(2)
    expect(wrapper.find('.entry-inherited-photos img').attributes('src')).toBe('/img/thumb/a.jpg')
    // 本地照片区不受影响（继承图不占 9 枚额度、无删除角标）
    expect(wrapper.findAll('.entry-photo-remove')).toHaveLength(0)
  })

  it('a failed inherited-photo fetch does not block re-entry (banner stays, submit still works)', async () => {
    apiMocks.fetchItemImages.mockRejectedValue(new ApiError(0, 'NETWORK_ERROR'))
    apiMocks.createItem.mockResolvedValue({ ...itemFixture, id: 32 })
    const wrapper = mountForm({ reEntry: voidedItem })
    await flushPromises()

    expect(wrapper.text()).toContain('HT9-A3X の再登録')
    expect(wrapper.findAll('.entry-inherited-photos img')).toHaveLength(0)

    await wrapper.find('form').trigger('submit')
    await waitFor(() => wrapper.emitted('saved')?.[0])
    expect(apiMocks.createItem).toHaveBeenCalledTimes(1)
  })

  it('やめる emits cancelReEntry (abandon re-entry and return to normal entry)', async () => {
    apiMocks.fetchItemImages.mockResolvedValue([])
    const wrapper = mountForm({ reEntry: voidedItem })
    await flushPromises()

    await wrapper.find('.entry-reentry-cancel').trigger('click')
    expect(wrapper.emitted('cancelReEntry')).toHaveLength(1)
  })
})
