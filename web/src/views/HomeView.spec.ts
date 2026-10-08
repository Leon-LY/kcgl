import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  fetchChecklist: vi.fn(),
  markChecklistPrintDone: vi.fn(),
}))

// ApiError 保持真实实现；仅替换 checklist 网络端点（SetupChecklistCard 发起）
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchChecklist: apiMocks.fetchChecklist,
    markChecklistPrintDone: apiMocks.markChecklistPrintDone,
  }
})

const pushMock = vi.hoisted(() => vi.fn())
const resolveMock = vi.hoisted(() => vi.fn())
const routeMock = vi.hoisted(() => ({ query: {} as Record<string, unknown> }))

// HomeView 用 useRouter（导航 + 按原路径解析页面标题）与 useRoute（读守卫回跳带的
// ?desktopOnly）；提供最小桩以便断言导航目标与提示文案
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRouter: () => ({ push: pushMock, resolve: resolveMock }),
    useRoute: () => routeMock,
  }
})

import HomeView from './HomeView.vue'
import { i18n } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import type { MeResponse } from '@/utils/api'

/**
 * 移动首页（D-155 后）：欢迎语 + 共享首启引导卡 + 作业入口磁贴 + 表示切替分段。
 * 桌面切壳跳大盘（D-072）。
 */

const meAdmin: MeResponse = {
  id: 1,
  username: 'boss',
  displayName: '管理者',
  role: 1,
  locale: 'ja-JP',
  mustChangePwd: false,
}

async function mountView(): Promise<VueWrapper> {
  const auth = useAuthStore()
  auth.me = meAdmin
  const wrapper = mount(HomeView, {
    global: { plugins: [i18n] },
  })
  await flushPromises()
  return wrapper
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  vi.resetAllMocks()
  // admin 挂载必触发 SetupChecklistCard 的 onMounted 取数——裸 mock 返回
  // undefined 会让 checklist 落成 undefined（类型契约外）并爆未处理拒绝。
  // 默认给「全部完成」态（卡片隐藏，与本文件各用例断言无交集）。
  apiMocks.fetchChecklist.mockResolvedValue({
    hasStaffUser: true,
    hasVenue: true,
    hasPriceBand: true,
    hasItem: true,
    printDone: true,
  })
  setActivePinia(createPinia())
  localStorage.clear()
  i18n.global.locale.value = 'ja-JP'
  // 默认：不是被守卫拦回来的普通访问（query 空、解析不出标题）
  routeMock.query = {}
  resolveMock.mockReturnValue({ meta: {} })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('home view', () => {
  it('renders the welcome from the session and carries no account card', async () => {
    const wrapper = await mountView()

    expect(wrapper.find('.home-welcome').text()).toContain('管理者')
    // 账号信息卡已整块删除（D-155，用户判定「没有任何意义」）：四项里显示名在欢迎语里、
    // 语言在顶栏切换器里，用户名与权限对本人无用。文案 + 结构双锁，残留任一项都拦得住。
    expect(wrapper.text()).not.toContain('アカウント情報')
    expect(wrapper.text()).not.toContain('boss')
    expect(wrapper.find('.home-info').exists()).toBe(false)
  })

  it('keeps the display switch a labelled group after its visible heading was dropped', async () => {
    const wrapper = await mountView()

    const group = wrapper.find('.home-shell-options')
    expect(group.attributes('role')).toBe('group')
    // 标题从视觉上撤掉（它与两颗按钮自述的内容重复），语义不能跟着撤
    expect(group.attributes('aria-label')).toBe('表示切替')
    expect(group.findAll('.home-shell-option')).toHaveLength(2)
  })

  it('embeds the shared setup checklist card for admins', async () => {
    apiMocks.fetchChecklist.mockResolvedValue({
      hasStaffUser: true,
      hasVenue: true,
      hasPriceBand: true,
      hasItem: false,
      printDone: false,
    })
    const wrapper = await mountView()

    expect(apiMocks.fetchChecklist).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.setup-card').exists()).toBe(true)
  })

  it('switching to the desktop shell navigates to the dashboard (D-072 landing)', async () => {
    const wrapper = await mountView()

    const desktopOption = wrapper
      .findAll('.home-shell-option')
      .find((b) => b.text() === 'パソコン表示')
    expect(desktopOption).toBeDefined()
    await desktopOption!.trigger('click')

    expect(pushMock).toHaveBeenCalledWith({ name: 'dashboard' })
  })

  it('staying on the mobile shell does not navigate', async () => {
    const wrapper = await mountView()

    const mobileOption = wrapper
      .findAll('.home-shell-option')
      .find((b) => b.text() === 'スマホ表示')
    await mobileOption!.trigger('click')

    expect(pushMock).not.toHaveBeenCalled()
  })

  it('shows no notice on a normal visit', async () => {
    const wrapper = await mountView()

    expect(wrapper.find('.home-notice').exists()).toBe(false)
  })

  it('explains a desktop-only bounce-back and opens it in the desktop shell', async () => {
    // 守卫把 /print 拦回 { home, ?desktopOnly=/print }（router.spec 覆盖守卫本身）
    routeMock.query = { desktopOnly: '/print' }
    resolveMock.mockReturnValue({ meta: { titleKey: 'print.title' } })
    const wrapper = await mountView()

    const notice = wrapper.find('.home-notice')
    expect(notice.exists()).toBe(true)
    // 说清用户点的是哪个页面，再给一条出路
    expect(notice.text()).toContain('ラベル印刷')
    expect(notice.text()).toContain('パソコン表示')

    await wrapper.find('.home-notice-action').trigger('click')

    expect(localStorage.getItem('kcgl-shell')).toBe('desktop')
    expect(pushMock).toHaveBeenCalledWith('/print')
  })

  it('falls back to a plain sentence when the bounced path has no title', async () => {
    routeMock.query = { desktopOnly: '/gone' }
    const wrapper = await mountView()

    expect(wrapper.find('.home-notice').text()).toContain(
      'この画面はパソコン表示でのみ開けます',
    )
  })
})
