import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  me: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  changeLocale: vi.fn(),
}))

vi.mock('@/utils/api', () => ({
  api: apiMocks,
}))

import { useAuthStore } from './auth'
import { i18n } from '@/i18n'

function meFixture(overrides: Partial<{ username: string; displayName: string; role: number; locale: string; mustChangePwd: boolean }> = {}) {
  return {
    username: 'taro',
    displayName: '田中太郎',
    role: 2,
    locale: 'ja-JP',
    mustChangePwd: false,
    ...overrides,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  vi.clearAllMocks()
  i18n.global.locale.value = 'ja-JP'
})

describe('initialize 启动恢复', () => {
  it('未登录（会话失效/401）→ me 为空但不抛出，initialized 置位', async () => {
    apiMocks.me.mockRejectedValue(new Error('401'))
    const auth = useAuthStore()
    await expect(auth.initialize()).resolves.toBeUndefined()
    expect(auth.me).toBeNull()
    expect(auth.initialized).toBe(true)
  })

  it('本地语言偏好先行应用（登录页即刻正确显示）', async () => {
    localStorage.setItem('kcgl-locale', 'zh-CN')
    apiMocks.me.mockRejectedValue(new Error('401'))
    const auth = useAuthStore()
    await auth.initialize()
    expect(i18n.global.locale.value).toBe('zh-CN')
  })

  it('已登录 → me 快照 + 语言跟随账号设置（账号值覆盖本地偏好）', async () => {
    localStorage.setItem('kcgl-locale', 'zh-CN')
    apiMocks.me.mockResolvedValue(meFixture({ locale: 'en-US' }))
    const auth = useAuthStore()
    await auth.initialize()
    expect(auth.me?.username).toBe('taro')
    expect(i18n.global.locale.value).toBe('en-US')
  })

  it('非法本地偏好值被忽略', async () => {
    localStorage.setItem('kcgl-locale', 'fr-FR')
    apiMocks.me.mockRejectedValue(new Error('401'))
    const auth = useAuthStore()
    await auth.initialize()
    expect(i18n.global.locale.value).toBe('ja-JP')
  })
})

describe('login', () => {
  it('成功 → me 设置 + 语言同步 + 本地持久化', async () => {
    apiMocks.login.mockResolvedValue(meFixture({ locale: 'zh-CN' }))
    const auth = useAuthStore()
    await auth.login('taro', 'password-123')
    expect(auth.me?.displayName).toBe('田中太郎')
    expect(i18n.global.locale.value).toBe('zh-CN')
    expect(localStorage.getItem('kcgl-locale')).toBe('zh-CN')
  })

  it('失败 → 异常上抛给登录页就地提示，me 保持空', async () => {
    apiMocks.login.mockRejectedValue(new Error('401'))
    const auth = useAuthStore()
    await expect(auth.login('taro', 'wrong')).rejects.toThrow()
    expect(auth.me).toBeNull()
  })
})

describe('logout', () => {
  it('服务端不可达也完成本地登出（防界面残留会话态）', async () => {
    apiMocks.me.mockResolvedValue(meFixture())
    const auth = useAuthStore()
    await auth.initialize()
    expect(auth.me).not.toBeNull()

    apiMocks.logout.mockRejectedValue(new Error('network down'))
    await expect(auth.logout()).rejects.toThrow()
    expect(auth.me).toBeNull()
  })
})

describe('setLocale', () => {
  it('游客态 → 即时生效+本地记忆，不调服务端', async () => {
    const auth = useAuthStore()
    await auth.setLocale('en-US')
    expect(i18n.global.locale.value).toBe('en-US')
    expect(localStorage.getItem('kcgl-locale')).toBe('en-US')
    expect(apiMocks.changeLocale).not.toHaveBeenCalled()
  })

  it('登录态 → 同步服务端并更新 me.locale', async () => {
    apiMocks.me.mockResolvedValue(meFixture())
    const auth = useAuthStore()
    await auth.initialize()
    apiMocks.changeLocale.mockResolvedValue(undefined)

    await auth.setLocale('zh-CN')
    expect(apiMocks.changeLocale).toHaveBeenCalledWith('zh-CN')
    expect(auth.me?.locale).toBe('zh-CN')
  })
})

describe('clearSession', () => {
  it('仅清本地会话态（401 全局处理回调专用）', async () => {
    apiMocks.me.mockResolvedValue(meFixture())
    const auth = useAuthStore()
    await auth.initialize()
    auth.clearSession()
    expect(auth.me).toBeNull()
    expect(apiMocks.logout).not.toHaveBeenCalled()
  })
})
