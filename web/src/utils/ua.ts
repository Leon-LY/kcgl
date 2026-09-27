/**
 * 壳选择（docs/01 4.3）：UA 自动识别 mobile/desktop + 手动切换（localStorage 记忆，
 * query 参数 ?shell= 一次性覆盖——发链接给同事时不带出个人偏好）。
 */

export type Shell = 'mobile' | 'desktop'

const SHELL_STORAGE_KEY = 'kcgl-shell'

export function detectShell(userAgent: string): Shell {
  return /Android|iPhone|iPad|iPod|Mobile/i.test(userAgent) ? 'mobile' : 'desktop'
}

/** 解析顺序：URL query 覆盖 > localStorage 用户偏好 > UA 检测。 */
export function resolveShell(userAgent: string, query?: URLSearchParams | null): Shell {
  const fromQuery = query?.get('shell')
  if (fromQuery === 'mobile' || fromQuery === 'desktop') {
    return fromQuery
  }
  const saved = localStorage.getItem(SHELL_STORAGE_KEY)
  if (saved === 'mobile' || saved === 'desktop') {
    return saved
  }
  return detectShell(userAgent)
}

export function saveShellPreference(shell: Shell): void {
  localStorage.setItem(SHELL_STORAGE_KEY, shell)
}
