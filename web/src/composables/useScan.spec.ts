import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { beep, createScanGate, vibrate } from './useScan'

describe('createScanGate', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    // 门逻辑只消费 Date.now() 的绝对毫秒：用 UTC epoch 固定基准（2026-09-28T10:00:00Z），不引入时区解析
    vi.setSystemTime(Date.UTC(2026, 8, 28, 10, 0, 0))
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('首扫放行；冷却窗内任意码丢弃（扫码枪连发回声）', () => {
    const gate = createScanGate()
    expect(gate.accept('HTK9-A1X')).toBe(true)
    vi.advanceTimersByTime(100)
    expect(gate.accept('HTK9-A2X')).toBe(false)
    vi.advanceTimersByTime(700)
    expect(gate.accept('HTK9-A2X')).toBe(true)
  })

  it('冷却窗外同码仍封锁 3s（慢速回声/双端重复触发）', () => {
    const gate = createScanGate()
    expect(gate.accept('HTK9-A1X')).toBe(true)
    vi.advanceTimersByTime(900) // 过冷却
    expect(gate.accept('HTK9-A1X')).toBe(false)
    vi.advanceTimersByTime(2100) // 同码窗累计 3s
    expect(gate.accept('HTK9-A1X')).toBe(true)
  })

  it('换码不受同码窗约束（连续扫不同件）', () => {
    const gate = createScanGate()
    expect(gate.accept('HTK9-A1X')).toBe(true)
    vi.advanceTimersByTime(800)
    expect(gate.accept('HTK9-A2X')).toBe(true)
  })

  it('reset 解锁同码重扫（动作完成后重新定位同一件）', () => {
    const gate = createScanGate()
    expect(gate.accept('HTK9-A1X')).toBe(true)
    gate.reset()
    expect(gate.accept('HTK9-A1X')).toBe(true)
  })

  it('自定义窗参数生效（短窗便于测试与特殊扫码枪适配）', () => {
    const gate = createScanGate({ cooldownMs: 100, sameCodeMs: 500 })
    expect(gate.accept('A1')).toBe(true)
    vi.advanceTimersByTime(150)
    expect(gate.accept('A1')).toBe(false)
    vi.advanceTimersByTime(400)
    expect(gate.accept('A1')).toBe(true)
  })
})

describe('scan feedback', () => {
  it('beep/vibrate 在接口缺失环境（测试/jsdom）不抛错', () => {
    expect(() => beep()).not.toThrow()
    expect(() => vibrate()).not.toThrow()
  })
})
