import { ref } from 'vue'
import { resolveShell, saveShellPreference, type Shell } from '@/utils/ua'

/**
 * 当前壳的 SPA 单例（docs/01 4.3 双壳）：应用启动解析一次
 * （query ?shell= 一次性覆盖 > localStorage 偏好 > UA 检测），
 * 手动切换即时生效并写入偏好。壳组件与切换入口共用本状态。
 */

const current = ref<Shell>(resolveShell(navigator.userAgent, new URLSearchParams(window.location.search)))

export function useShell() {
  function switchShell(shell: Shell): void {
    current.value = shell
    saveShellPreference(shell)
  }

  return { shell: current, switchShell }
}
