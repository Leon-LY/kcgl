import { beforeEach, describe, expect, it } from 'vitest'
import { useShell } from './useShell'

describe('useShell', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('resolves the initial shell to a valid value (from current UA/preference)', () => {
    const { shell } = useShell()
    expect(['mobile', 'desktop']).toContain(shell.value)
  })

  it('switchShell takes effect immediately and persists the preference for the next visit', () => {
    const { shell, switchShell } = useShell()
    switchShell('desktop')
    expect(shell.value).toBe('desktop')
    expect(localStorage.getItem('kcgl-shell')).toBe('desktop')

    switchShell('mobile')
    expect(shell.value).toBe('mobile')
    expect(localStorage.getItem('kcgl-shell')).toBe('mobile')
  })

  it('shares the same state across multiple calls (SPA singleton)', () => {
    const first = useShell()
    const second = useShell()
    first.switchShell('desktop')
    expect(second.shell.value).toBe('desktop')
  })
})
