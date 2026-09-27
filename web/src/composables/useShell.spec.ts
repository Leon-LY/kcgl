import { beforeEach, describe, expect, it } from 'vitest'
import { useShell } from './useShell'

describe('useShell', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('初始壳为合法值（按当前 UA/偏好解析）', () => {
    const { shell } = useShell()
    expect(['mobile', 'desktop']).toContain(shell.value)
  })

  it('switchShell 即时生效并写入偏好（下次访问恢复）', () => {
    const { shell, switchShell } = useShell()
    switchShell('desktop')
    expect(shell.value).toBe('desktop')
    expect(localStorage.getItem('kcgl-shell')).toBe('desktop')

    switchShell('mobile')
    expect(shell.value).toBe('mobile')
    expect(localStorage.getItem('kcgl-shell')).toBe('mobile')
  })

  it('多处调用共享同一状态（SPA 单例）', () => {
    const first = useShell()
    const second = useShell()
    first.switchShell('desktop')
    expect(second.shell.value).toBe('desktop')
  })
})
