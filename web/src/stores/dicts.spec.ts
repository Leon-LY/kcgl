import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  fetchVenues: vi.fn(),
  fetchPriceBands: vi.fn(),
}))

vi.mock('@/utils/api', () => ({
  fetchVenues: apiMocks.fetchVenues,
  fetchPriceBands: apiMocks.fetchPriceBands,
}))

import { useDictsStore } from './dicts'

const venueFixture = (id: number, code: string, enabled: boolean) => ({
  id,
  code,
  name: `会場${id}`,
  enabled,
})

const bandFixture = (code: string, lowerBound: number | null, upperBound: number | null, enabled = true) => ({
  id: code.charCodeAt(0),
  code,
  lowerBound,
  upperBound,
  enabled,
})

function seedDicts(): void {
  apiMocks.fetchVenues.mockResolvedValue([
    venueFixture(1, 'HT', true),
    venueFixture(2, 'NG', false),
  ])
  apiMocks.fetchPriceBands.mockResolvedValue([
    bandFixture('X', 0, 3000),
    bandFixture('Y', 3000, 10000),
    bandFixture('Z', 10000, null),
    bandFixture('D', null, 10, false),
  ])
}

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  vi.clearAllMocks()
})

describe('ensureLoaded', () => {
  it('并发调用共享同一次请求（后到者不空手而归）', async () => {
    seedDicts()
    const dicts = useDictsStore()
    const calls = [dicts.ensureLoaded(), dicts.ensureLoaded(), dicts.ensureLoaded()]
    await Promise.all(calls)
    expect(apiMocks.fetchVenues).toHaveBeenCalledTimes(1)
    expect(apiMocks.fetchPriceBands).toHaveBeenCalledTimes(1)
    expect(dicts.loaded).toBe(true)
    expect(dicts.loading).toBe(false)
  })

  it('失败后再次调用会重试（inflight 清空）', async () => {
    apiMocks.fetchVenues.mockRejectedValueOnce(new Error('network'))
    apiMocks.fetchPriceBands.mockResolvedValue([])
    const dicts = useDictsStore()
    await expect(dicts.ensureLoaded()).rejects.toThrow('network')
    expect(dicts.loadError).toBe('network')

    apiMocks.fetchVenues.mockResolvedValue([venueFixture(1, 'HT', true)])
    await dicts.ensureLoaded()
    expect(dicts.loaded).toBe(true)
    expect(apiMocks.fetchVenues).toHaveBeenCalledTimes(2)
  })

  it('已加载后不再请求', async () => {
    seedDicts()
    const dicts = useDictsStore()
    await dicts.ensureLoaded()
    await dicts.ensureLoaded()
    expect(apiMocks.fetchVenues).toHaveBeenCalledTimes(1)
  })
})

describe('reload', () => {
  it('强制重取（字典管理改动/SSE 失效广播）', async () => {
    seedDicts()
    const dicts = useDictsStore()
    await dicts.ensureLoaded()
    apiMocks.fetchVenues.mockResolvedValue([venueFixture(9, 'OS', true)])
    await dicts.reload()
    expect(apiMocks.fetchVenues).toHaveBeenCalledTimes(2)
    expect(dicts.enabledVenues).toHaveLength(1)
    expect(dicts.enabledVenues[0]?.id).toBe(9)
  })
})

describe('enabledVenues', () => {
  it('过滤停用会场（录入下拉只给启用）', async () => {
    seedDicts()
    const dicts = useDictsStore()
    await dicts.ensureLoaded()
    expect(dicts.enabledVenues.map((venue) => venue.code)).toEqual(['HT'])
  })
})

describe('matchBand（左闭右开，与服务端同语义）', () => {
  it('边界值：下界含、上界不含', async () => {
    seedDicts()
    const dicts = useDictsStore()
    await dicts.ensureLoaded()

    expect(dicts.matchBand(0)).toBeNull() // 0/负值不是有效单价
    expect(dicts.matchBand(1)?.code).toBe('X')
    expect(dicts.matchBand(2999)?.code).toBe('X')
    expect(dicts.matchBand(3000)?.code).toBe('Y')
    expect(dicts.matchBand(9999)?.code).toBe('Y')
    expect(dicts.matchBand(10000)?.code).toBe('Z')
  })

  it('停用档位不参与匹配（历史快照不回溯）', async () => {
    seedDicts()
    const dicts = useDictsStore()
    await dicts.ensureLoaded()
    // D 档（null-10）已停用：低价位应命中 X 而非 D
    expect(dicts.matchBand(5)?.code).toBe('X')
  })

  it('空档位表/非法单价 → null', async () => {
    apiMocks.fetchVenues.mockResolvedValue([])
    apiMocks.fetchPriceBands.mockResolvedValue([])
    const dicts = useDictsStore()
    await dicts.ensureLoaded()
    expect(dicts.matchBand(100)).toBeNull()
    expect(dicts.matchBand(null)).toBeNull()
    expect(dicts.matchBand(Number.NaN)).toBeNull()
  })
})
