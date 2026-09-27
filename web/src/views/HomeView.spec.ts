import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  fetchChecklist: vi.fn(),
  markChecklistPrintDone: vi.fn(),
}))

// ApiError 保持真实实现；仅替换 checklist 网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchChecklist: apiMocks.fetchChecklist,
    markChecklistPrintDone: apiMocks.markChecklistPrintDone,
  }
})

const pushMock = vi.hoisted(() => vi.fn())

// HomeView 仅用 useRouter；提供最小 push 桩以便断言导航目标
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRouter: () => ({ push: pushMock }),
  }
})

import HomeView from './HomeView.vue'
import { i18n } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import type { Checklist, MeResponse } from '@/utils/api'

/**
 * 首启 checklist 卡片（M2-8b-3）：管理员可见五步/完成打勾/
 * 印刷标记后置灰勾选/全部完成隐藏/编辑者不拉取不显示/拉取失败静默。
 */

const meAdmin: MeResponse = {
  username: 'boss',
  displayName: '管理者',
  role: 1,
  locale: 'ja-JP',
  mustChangePwd: false,
}

const meEditor: MeResponse = {
  username: 'eichi',
  displayName: '編集者',
  role: 2,
  locale: 'ja-JP',
  mustChangePwd: false,
}

function checklist(overrides: Partial<Checklist>): Checklist {
  return {
    hasStaffUser: true,
    hasVenue: true,
    hasPriceBand: true,
    hasItem: true,
    printDone: true,
    ...overrides,
  }
}

async function mountView(role: 1 | 2 = 1): Promise<VueWrapper> {
  const auth = useAuthStore()
  auth.me = role === 1 ? meAdmin : meEditor
  const wrapper = mount(HomeView, {
    global: { plugins: [i18n] },
  })
  await flushPromises()
  return wrapper
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  vi.resetAllMocks()
  setActivePinia(createPinia())
  i18n.global.locale.value = 'ja-JP'
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('home setup checklist (M2-8b-3)', () => {
  it('shows the five steps for an admin with incomplete setup', async () => {
    apiMocks.fetchChecklist.mockResolvedValue(checklist({ hasItem: false, printDone: false }))
    const wrapper = await mountView()

    expect(apiMocks.fetchChecklist).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.home-setup').exists()).toBe(true)
    const steps = wrapper.findAll('.home-setup-step')
    expect(steps).toHaveLength(5)
    // 前 3 步完成（种子员工/会场/档位），第 4-5 步未完成
    expect(wrapper.findAll('.home-setup-step.is-done')).toHaveLength(3)
    expect(wrapper.text()).toContain('商品を1件登録してみる')
    expect(wrapper.text()).toContain('ラベルを1枚印刷してみる')
  })

  it('navigates a step link to its admin page', async () => {
    apiMocks.fetchChecklist.mockResolvedValue(checklist({ printDone: false }))
    const wrapper = await mountView()

    await wrapper.findAll('.home-setup-link')[0]!.trigger('click')
    expect(pushMock).toHaveBeenCalledWith('/admin/users')
  })

  it('marks print done and re-renders the step state', async () => {
    apiMocks.fetchChecklist.mockResolvedValue(checklist({ hasItem: false, printDone: false }))
    // 响应：印刷完成但仍未录件 → 卡片保留、第 5 步打勾、按钮消失
    apiMocks.markChecklistPrintDone.mockResolvedValue(checklist({ hasItem: false, printDone: true }))
    const wrapper = await mountView()

    await wrapper.find('.home-setup-done').trigger('click')
    await flushPromises()

    expect(apiMocks.markChecklistPrintDone).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.home-setup').exists()).toBe(true)
    expect(wrapper.findAll('.home-setup-step.is-done')).toHaveLength(4)
    expect(wrapper.find('.home-setup-done').exists()).toBe(false)
  })

  it('hides the card once every step is done', async () => {
    apiMocks.fetchChecklist.mockResolvedValue(checklist({}))
    const wrapper = await mountView()

    expect(apiMocks.fetchChecklist).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.home-setup').exists()).toBe(false)
  })

  it('does not fetch the checklist for a non-admin', async () => {
    const wrapper = await mountView(2)

    expect(apiMocks.fetchChecklist).not.toHaveBeenCalled()
    expect(wrapper.find('.home-setup').exists()).toBe(false)
  })

  it('stays silent when the checklist cannot be loaded', async () => {
    apiMocks.fetchChecklist.mockRejectedValue(new Error('network down'))
    const wrapper = await mountView()

    expect(apiMocks.fetchChecklist).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.home-setup').exists()).toBe(false)
    expect(wrapper.text()).toContain('管理者')
  })
})
