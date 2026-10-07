import { describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { useMediaQuery } from './useMediaQuery'

/**
 * 媒体查询出口：初始值、change 事件跟随、脱离作用域时解绑监听。
 * setup.ts 的桩默认 matches=false 且事件为空实现，故每个用例都自己覆写。
 */

function stubMatchMedia(matches: boolean): { onChange: (m: boolean) => void; removed: () => number } {
  let handler: ((event: { matches: boolean }) => void) | null = null
  let removedCount = 0
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn((type: string, cb: (event: { matches: boolean }) => void) => {
        if (type === 'change') handler = cb
      }),
      removeEventListener: vi.fn(() => {
        removedCount += 1
      }),
      dispatchEvent: vi.fn(),
    })),
  })
  return {
    onChange: (next: boolean) => handler?.({ matches: next }),
    removed: () => removedCount,
  }
}

describe('useMediaQuery', () => {
  it('takes the initial value from the media list, not from a later event', () => {
    stubMatchMedia(true)

    const scope = effectScope()
    const matches = scope.run(() => useMediaQuery('(min-width: 1680px)'))!

    expect(matches.value).toBe(true)
    scope.stop()
  })

  it('follows change events from the media list', () => {
    const media = stubMatchMedia(false)

    const scope = effectScope()
    const matches = scope.run(() => useMediaQuery('(min-width: 1680px)'))!
    expect(matches.value).toBe(false)

    media.onChange(true)
    expect(matches.value).toBe(true)

    scope.stop()
  })

  it('falls back to false when matchMedia is unavailable', () => {
    const original = window.matchMedia
    Object.defineProperty(window, 'matchMedia', { writable: true, value: undefined })

    const scope = effectScope()
    const matches = scope.run(() => useMediaQuery('(min-width: 1680px)'))!
    expect(matches.value).toBe(false)
    scope.stop()

    Object.defineProperty(window, 'matchMedia', { writable: true, value: original })
  })

  it('removes the change listener when the scope is disposed', () => {
    const media = stubMatchMedia(false)

    const scope = effectScope()
    scope.run(() => useMediaQuery('(min-width: 1680px)'))
    expect(media.removed()).toBe(0)

    scope.stop()
    expect(media.removed()).toBe(1)
  })
})
