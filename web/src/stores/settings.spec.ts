import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  fetchSettings: vi.fn(),
}))

vi.mock('@/utils/api', () => ({
  fetchSettings: apiMocks.fetchSettings,
}))

import { useSettingsStore } from './settings'

const SETTINGS_A = {
  warnDays: 30,
  alarmDays: 90,
  labelPreset: 'small' as const,
  labelWidthMm: 50,
  labelHeightMm: 30,
}

const SETTINGS_B = {
  warnDays: 45,
  alarmDays: 100,
  labelPreset: 'custom' as const,
  labelWidthMm: 60,
  labelHeightMm: 40,
}

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  vi.clearAllMocks()
})

describe('ensureLoaded', () => {
  it('shares a single in-flight request across concurrent callers', async () => {
    apiMocks.fetchSettings.mockResolvedValue(SETTINGS_A)
    const settings = useSettingsStore()
    await Promise.all([settings.ensureLoaded(), settings.ensureLoaded()])
    expect(apiMocks.fetchSettings).toHaveBeenCalledTimes(1)
    expect(settings.loaded).toBe(true)
    expect(settings.data).toEqual(SETTINGS_A)
    expect(settings.loading).toBe(false)
  })

  it('does not pre-fabricate data before load (no invented defaults)', () => {
    const settings = useSettingsStore()
    expect(settings.data).toBeNull()
    expect(settings.loaded).toBe(false)
  })

  it('retries on the next call after a failure (in-flight cleared)', async () => {
    apiMocks.fetchSettings.mockRejectedValueOnce(new Error('network'))
    const settings = useSettingsStore()
    await expect(settings.ensureLoaded()).rejects.toThrow('network')

    apiMocks.fetchSettings.mockResolvedValue(SETTINGS_A)
    await settings.ensureLoaded()
    expect(settings.loaded).toBe(true)
    expect(apiMocks.fetchSettings).toHaveBeenCalledTimes(2)
  })

  it('skips refetching once loaded', async () => {
    apiMocks.fetchSettings.mockResolvedValue(SETTINGS_A)
    const settings = useSettingsStore()
    await settings.ensureLoaded()
    await settings.ensureLoaded()
    expect(apiMocks.fetchSettings).toHaveBeenCalledTimes(1)
  })
})

describe('reload (SSE SETTING invalidation path)', () => {
  it('forces a refetch and swaps in the new snapshot', async () => {
    apiMocks.fetchSettings.mockResolvedValue(SETTINGS_A)
    const settings = useSettingsStore()
    await settings.ensureLoaded()

    apiMocks.fetchSettings.mockResolvedValue(SETTINGS_B)
    await settings.reload()
    expect(apiMocks.fetchSettings).toHaveBeenCalledTimes(2)
    expect(settings.data).toEqual(SETTINGS_B)
  })

  it('keeps the stale snapshot when the refetch fails (broadcast chain stays silent)', async () => {
    apiMocks.fetchSettings.mockResolvedValue(SETTINGS_A)
    const settings = useSettingsStore()
    await settings.ensureLoaded()

    apiMocks.fetchSettings.mockRejectedValue(new Error('network'))
    await expect(settings.reload()).resolves.toBeUndefined()
    expect(settings.data).toEqual(SETTINGS_A)
    expect(settings.loaded).toBe(false) // 失败不复位已加载态以外的事实：下次 ensureLoaded 重试
  })
})
