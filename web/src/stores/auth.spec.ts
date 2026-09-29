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

function meFixture(overrides: Partial<{ id: number; username: string; displayName: string; role: number; locale: string; mustChangePwd: boolean }> = {}) {
  return {
    id: 101,
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

describe('initialize startup restore', () => {
  it('keeps me empty without throwing on 401 and marks initialized', async () => {
    apiMocks.me.mockRejectedValue(new Error('401'))
    const auth = useAuthStore()
    await expect(auth.initialize()).resolves.toBeUndefined()
    expect(auth.me).toBeNull()
    expect(auth.initialized).toBe(true)
  })

  it('applies the saved locale preference first (login page renders correctly at once)', async () => {
    localStorage.setItem('kcgl-locale', 'zh-CN')
    apiMocks.me.mockRejectedValue(new Error('401'))
    const auth = useAuthStore()
    await auth.initialize()
    expect(i18n.global.locale.value).toBe('zh-CN')
  })

  it('restores the me snapshot when logged in and follows the account locale (account value overrides the local preference)', async () => {
    localStorage.setItem('kcgl-locale', 'zh-CN')
    apiMocks.me.mockResolvedValue(meFixture({ locale: 'en-US' }))
    const auth = useAuthStore()
    await auth.initialize()
    expect(auth.me?.username).toBe('taro')
    expect(i18n.global.locale.value).toBe('en-US')
  })

  it('ignores invalid saved locale values', async () => {
    localStorage.setItem('kcgl-locale', 'fr-FR')
    apiMocks.me.mockRejectedValue(new Error('401'))
    const auth = useAuthStore()
    await auth.initialize()
    expect(i18n.global.locale.value).toBe('ja-JP')
  })
})

describe('login', () => {
  it('sets me, syncs the locale, and persists it locally on success', async () => {
    apiMocks.login.mockResolvedValue(meFixture({ locale: 'zh-CN' }))
    const auth = useAuthStore()
    await auth.login('taro', 'password-123')
    expect(auth.me?.displayName).toBe('田中太郎')
    expect(i18n.global.locale.value).toBe('zh-CN')
    expect(localStorage.getItem('kcgl-locale')).toBe('zh-CN')
  })

  it('rethrows on failure for inline login-page feedback and keeps me empty', async () => {
    apiMocks.login.mockRejectedValue(new Error('401'))
    const auth = useAuthStore()
    await expect(auth.login('taro', 'wrong')).rejects.toThrow()
    expect(auth.me).toBeNull()
  })
})

describe('logout', () => {
  it('completes local logout even when the server is unreachable (no stale session state left on screen)', async () => {
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
  it('applies instantly with local persistence for guests, without calling the server', async () => {
    const auth = useAuthStore()
    await auth.setLocale('en-US')
    expect(i18n.global.locale.value).toBe('en-US')
    expect(localStorage.getItem('kcgl-locale')).toBe('en-US')
    expect(apiMocks.changeLocale).not.toHaveBeenCalled()
  })

  it('syncs with the server and updates me.locale when logged in', async () => {
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
  it('clears local session state only (reserved for the global 401 handler)', async () => {
    apiMocks.me.mockResolvedValue(meFixture())
    const auth = useAuthStore()
    await auth.initialize()
    auth.clearSession()
    expect(auth.me).toBeNull()
    expect(apiMocks.logout).not.toHaveBeenCalled()
  })
})
