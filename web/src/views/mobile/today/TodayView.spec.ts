import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  fetchTodaySession: vi.fn(),
}))

// ApiError 保持真实实现（错误分支依赖 instanceof/code）；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchTodaySession: apiMocks.fetchTodaySession,
  }
})

import TodayView from './TodayView.vue'
import { i18n } from '@/i18n'
import { ApiError } from '@/utils/api'
import type { TodaySession } from '@/utils/api'

/**
 * 本日录入会话页（M2-8b）：个人当天清单渲染（含作废件=对数口径）/
 * 计数头/空态/加载失败重试。不分页（一次全量），无 van-list 依赖。
 */

function session(overrides: Partial<TodaySession> = {}): TodaySession {
  return {
    date: '2026-09-27',
    activeCount: 2,
    voidedCount: 1,
    rows: [
      {
        id: 301,
        itemCode: 'HTK9-A1X',
        voided: true,
        voidReason: '価格入力ミス',
        createdAt: '09:15',
        thumbUrl: null,
      },
      {
        id: 302,
        itemCode: 'HTK9-A2X',
        voided: false,
        voidReason: null,
        createdAt: '10:02',
        thumbUrl: '/img/thumb/302.jpg',
      },
      {
        id: 303,
        itemCode: 'HTK9-A3X',
        voided: false,
        voidReason: null,
        createdAt: '11:47',
        thumbUrl: null,
      },
    ],
    ...overrides,
  }
}

function rows(wrapper: VueWrapper) {
  return wrapper.findAll('.today-row')
}

async function mountView(): Promise<VueWrapper> {
  const wrapper = mount(TodayView, {
    global: { plugins: [i18n] },
  })
  await flushPromises()
  return wrapper
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  vi.resetAllMocks()
  // 视图经 useSyncInvalidation 订阅 sync store（他端失效重取）——store 需 pinia
  setActivePinia(createPinia())
  i18n.global.locale.value = 'ja-JP'
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('today session (M2-8b)', () => {
  it('renders my entries in order with counts and the entry time', async () => {
    apiMocks.fetchTodaySession.mockResolvedValue(session())
    const wrapper = await mountView()

    expect(apiMocks.fetchTodaySession).toHaveBeenCalledTimes(1)
    expect(rows(wrapper)).toHaveLength(3)
    expect(wrapper.text()).toContain('本日 2 件・取り消し 1 件')
    expect(wrapper.text()).toContain('2026-09-27 の登録分')
    expect(wrapper.text()).toContain('HTK9-A2X')
    expect(wrapper.text()).toContain('10:02')
  })

  it('marks voided rows with a strikethrough code, a tag, and the reason', async () => {
    apiMocks.fetchTodaySession.mockResolvedValue(session())
    const wrapper = await mountView()

    const voidedRow = rows(wrapper)[0]!
    expect(voidedRow.classes()).toContain('is-voided')
    expect(voidedRow.find('.today-void-tag').text()).toBe('取り消し')
    expect(voidedRow.find('.today-void-reason').text()).toBe('価格入力ミス')

    const liveRow = rows(wrapper)[1]!
    expect(liveRow.classes()).not.toContain('is-voided')
    expect(liveRow.find('.today-void-tag').exists()).toBe(false)
  })

  it('renders a thumbnail image when the row has one', async () => {
    apiMocks.fetchTodaySession.mockResolvedValue(session())
    const wrapper = await mountView()

    const img = rows(wrapper)[1]!.find('.today-thumb img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('/img/thumb/302.jpg')
    expect(rows(wrapper)[0]!.find('.today-thumb img').exists()).toBe(false)
  })

  it('shows the empty state when nothing was entered today', async () => {
    apiMocks.fetchTodaySession.mockResolvedValue(
      session({ activeCount: 0, voidedCount: 0, rows: [] }),
    )
    const wrapper = await mountView()

    expect(wrapper.find('.today-empty').exists()).toBe(true)
    expect(wrapper.text()).toContain('本日の登録はまだありません')
    expect(wrapper.text()).toContain('本日 0 件・取り消し 0 件')
  })

  it('shows an error with a reload button and recovers on retry', async () => {
    apiMocks.fetchTodaySession.mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR'))
    const wrapper = await mountView()

    expect(wrapper.find('.today-error').exists()).toBe(true)
    expect(wrapper.text()).toContain('読み込みに失敗しました')
    expect(rows(wrapper)).toHaveLength(0)

    apiMocks.fetchTodaySession.mockResolvedValueOnce(session())
    await wrapper.find('.today-retry').trigger('click')
    await flushPromises()

    expect(apiMocks.fetchTodaySession).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.today-error').exists()).toBe(false)
    expect(rows(wrapper)).toHaveLength(3)
  })
})
