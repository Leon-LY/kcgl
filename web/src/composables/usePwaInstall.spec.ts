import { beforeEach, describe, expect, it, vi } from 'vitest'
import { usePwaInstall } from './usePwaInstall'

/**
 * 主屏安装引导状态（M6-①）：standalone 双通道检测、「後で」记忆、
 * beforeinstallprompt 捕获与 promptInstall 结果回传。
 */

const pwa = usePwaInstall()

/** 覆写 matchMedia 假实现（setup.ts 默认 matches=false）。 */
function stubMatchMedia(matches: boolean): void {
  vi.mocked(window.matchMedia).mockImplementation((query: string) => ({
    matches,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }))
}

/** dispatch 一个最小 beforeinstallprompt 事件（Chrome/Android 安装时机）。 */
function fireInstallPrompt(): void {
  const event = new Event('beforeinstallprompt') as Event & {
    prompt: () => Promise<void>
    userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
  }
  event.prompt = vi.fn().mockResolvedValue(undefined)
  event.userChoice = Promise.resolve({ outcome: 'accepted' })
  window.dispatchEvent(event)
}

beforeEach(() => {
  localStorage.clear()
  pwa.resetForTests()
  stubMatchMedia(false)
})

describe('usePwaInstall (M6-1)', () => {
  it('shows the guide when running as a plain browser tab (not standalone, not dismissed)', () => {
    pwa.init()
    expect(pwa.state.standalone).toBe(false)
    expect(pwa.shouldShowGuide.value).toBe(true)
  })

  it('hides the guide permanently after 「後で」 dismissal (localStorage memory)', () => {
    pwa.init()
    pwa.dismissGuide()
    expect(pwa.shouldShowGuide.value).toBe(false)
    expect(localStorage.getItem('kcgl-pwa-guide-dismissed')).toBe('1')
  })

  it('detects standalone via the display-mode media query', () => {
    stubMatchMedia(true)
    pwa.init()
    expect(pwa.state.standalone).toBe(true)
    expect(pwa.shouldShowGuide.value).toBe(false)
  })

  it('captures beforeinstallprompt and reports the user choice from promptInstall', async () => {
    pwa.init()
    fireInstallPrompt()
    expect(pwa.state.canPrompt).toBe(true)

    const outcome = await pwa.promptInstall()
    expect(outcome).toBe('accepted')
    expect(pwa.state.canPrompt).toBe(false)
  })

  it('promptInstall returns unavailable when no prompt was captured (iOS path)', async () => {
    pwa.init()
    await expect(pwa.promptInstall()).resolves.toBe('unavailable')
  })
})
