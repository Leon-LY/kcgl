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

describe('初始状态', () => {
  it('无历史 → 落札日期默认当天 JST、计数 0', () => {
    const session = useEntrySessionStore()
    expect(session.buyDate).toBe(todayJst())
    expect(session.todayCount).toBe(0)
    expect(session.venueId).toBeNull()
    expect(session.warehouse).toBe(1)
  })

  it('损坏 JSON → 回退默认态不抛出', () => {
    localStorage.setItem('kcgl-entry-session', '{broken')
    const session = useEntrySessionStore()
    expect(session.todayCount).toBe(0)
  })
})

describe('localStorage 恢复（杀进程不丢）', () => {
  it('沿用值与计数从上次会话恢复', () => {
    seedSavedSession()
    const session = useEntrySessionStore()
    expect(session.venueId).toBe(7)
    expect(session.warehouse).toBe(2)
    expect(session.todayCount).toBe(3)
    expect(session.purchasePrice).toBe(1500)
  })
})

describe('recordSaved', () => {
  it('沿用字段取自落库结果 + 计数递增 + 持久化', () => {
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

describe('ensureToday（JST 日界滚动）', () => {
  it('跨日 → 计数清零、落札日期回今天；会场/仓库/单价仍沿用', () => {
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

  it('同日 → 不变', () => {
    seedSavedSession()
    const session = useEntrySessionStore()
    session.ensureToday()
    expect(session.todayCount).toBe(3)
  })
})

describe('isCarryDateStale（前日角标）', () => {
  it('沿用落札日 ≠ 今天且今日已有保存 → true', () => {
    seedSavedSession({ buyDate: yesterdayJst() })
    const session = useEntrySessionStore()
    expect(session.isCarryDateStale).toBe(true)
  })

  it('落札日为今天 → false', () => {
    seedSavedSession()
    const session = useEntrySessionStore()
    expect(session.isCarryDateStale).toBe(false)
  })

  it('首件（今日无保存）即使日期异常也不标 → false', () => {
    const session = useEntrySessionStore()
    session.$patch({ buyDate: yesterdayJst(), todayCount: 0 })
    expect(session.isCarryDateStale).toBe(false)
  })
})
