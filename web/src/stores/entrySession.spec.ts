import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { JST_TZ, dayjs } from '@/utils/format'

import { useEntrySessionStore } from './entrySession'

function todayJst(): string {
  return dayjs().tz(JST_TZ).format('YYYY-MM-DD')
}

function yesterdayJst(): string {
  return dayjs().tz(JST_TZ).subtract(1, 'day').format('YYYY-MM-DD')
}

const savedItemFixture = {
  venueId: 7,
  warehouse: 2,
  buyDate: '2026-09-15',
  purchasePrice: 1500,
}

function seedSavedSession(overrides: Record<string, unknown> = {}): void {
  localStorage.setItem(
    'kcgl-entry-session',
    JSON.stringify({
      venueId: 7,
      warehouse: 2,
      buyDate: todayJst(),
      purchasePrice: 1500,
      todayCount: 3,
      today: todayJst(),
      ...overrides,
    }),
  )
}

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
})

describe('initial state', () => {
  it('defaults the buy date to today JST with zero count when no history exists', () => {
    const session = useEntrySessionStore()
    expect(session.buyDate).toBe(todayJst())
    expect(session.todayCount).toBe(0)
    expect(session.venueId).toBeNull()
    expect(session.warehouse).toBe(1)
  })

  it('falls back to defaults without throwing on corrupted JSON', () => {
    localStorage.setItem('kcgl-entry-session', '{broken')
    const session = useEntrySessionStore()
    expect(session.todayCount).toBe(0)
  })
})

describe('localStorage restore (survives a process kill)', () => {
  it('restores carry-over values and count from the previous session', () => {
    seedSavedSession()
    const session = useEntrySessionStore()
    expect(session.venueId).toBe(7)
    expect(session.warehouse).toBe(2)
    expect(session.todayCount).toBe(3)
    expect(session.purchasePrice).toBe(1500)
  })
})

describe('recordSaved', () => {
  it('takes carry-over fields from the saved result, increments the count, and persists', () => {
    const session = useEntrySessionStore()
    session.recordSaved({ ...savedItemFixture, buyDate: todayJst() })
    expect(session.venueId).toBe(7)
    expect(session.warehouse).toBe(2)
    expect(session.buyDate).toBe(todayJst())
    expect(session.purchasePrice).toBe(1500)
    expect(session.todayCount).toBe(1)

    const persisted = JSON.parse(localStorage.getItem('kcgl-entry-session') ?? '{}')
    expect(persisted.todayCount).toBe(1)
    expect(persisted.venueId).toBe(7)
  })
})

describe('ensureToday (JST day rollover)', () => {
  it('rolls over across JST midnight: resets count and buy date, keeps carry-over values', () => {
    seedSavedSession({ buyDate: yesterdayJst(), today: yesterdayJst() })
    const session = useEntrySessionStore()
    session.ensureToday()
    expect(session.todayCount).toBe(0)
    expect(session.buyDate).toBe(todayJst())
    expect(session.venueId).toBe(7)
    expect(session.warehouse).toBe(2)
    expect(session.purchasePrice).toBe(1500)
    // 滚动结果写回存储
    const persisted = JSON.parse(localStorage.getItem('kcgl-entry-session') ?? '{}')
    expect(persisted.today).toBe(todayJst())
  })

  it('leaves state unchanged on the same day', () => {
    seedSavedSession()
    const session = useEntrySessionStore()
    session.ensureToday()
    expect(session.todayCount).toBe(3)
  })
})

describe('isCarryDateStale (stale-date badge)', () => {
  it('is true when the carried buy date is not today and today already has saves', () => {
    seedSavedSession({ buyDate: yesterdayJst() })
    const session = useEntrySessionStore()
    expect(session.isCarryDateStale).toBe(true)
  })

  it('is false when the buy date is today', () => {
    seedSavedSession()
    const session = useEntrySessionStore()
    expect(session.isCarryDateStale).toBe(false)
  })

  it('is false for the first entry of the day even when the date is off', () => {
    const session = useEntrySessionStore()
    session.$patch({ buyDate: yesterdayJst(), todayCount: 0 })
    expect(session.isCarryDateStale).toBe(false)
  })
})
