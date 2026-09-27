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
  it('shares a single in-flight request across concurrent callers', async () => {
    seedDicts()
    const dicts = useDictsStore()
    const calls = [dicts.ensureLoaded(), dicts.ensureLoaded(), dicts.ensureLoaded()]
    await Promise.all(calls)
    expect(apiMocks.fetchVenues).toHaveBeenCalledTimes(1)
    expect(apiMocks.fetchPriceBands).toHaveBeenCalledTimes(1)
    expect(dicts.loaded).toBe(true)
    expect(dicts.loading).toBe(false)
  })

  it('retries on the next call after a failure (in-flight cleared)', async () => {
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

  it('skips refetching once loaded', async () => {
    seedDicts()
    const dicts = useDictsStore()
    await dicts.ensureLoaded()
    await dicts.ensureLoaded()
    expect(apiMocks.fetchVenues).toHaveBeenCalledTimes(1)
  })
})

describe('reload', () => {
  it('forces a refetch (dict management changes / SSE invalidation broadcast)', async () => {
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
  it('filters out disabled venues (entry dropdown offers enabled ones only)', async () => {
    seedDicts()
    const dicts = useDictsStore()
    await dicts.ensureLoaded()
    expect(dicts.enabledVenues.map((venue) => venue.code)).toEqual(['HT'])
  })
})

describe('matchBand (half-open intervals, same semantics as the server)', () => {
  it('treats bounds as inclusive lower, exclusive upper', async () => {
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

  it('excludes disabled bands from matching (no retroactive history rewrite)', async () => {
    seedDicts()
    const dicts = useDictsStore()
    await dicts.ensureLoaded()
    // D 档（null-10）已停用：低价位应命中 X 而非 D
    expect(dicts.matchBand(5)?.code).toBe('X')
  })

  it('returns null for an empty band table or an invalid price', async () => {
    apiMocks.fetchVenues.mockResolvedValue([])
    apiMocks.fetchPriceBands.mockResolvedValue([])
    const dicts = useDictsStore()
    await dicts.ensureLoaded()
    expect(dicts.matchBand(100)).toBeNull()
    expect(dicts.matchBand(null)).toBeNull()
    expect(dicts.matchBand(Number.NaN)).toBeNull()
  })
})
