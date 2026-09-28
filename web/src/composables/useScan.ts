/**
 * 扫码交互原语（M3-④，docs/01 4.3 扫码页）：
 * - createScanGate：800ms 冷却（手持扫码枪连发回声）+ 同码 3s 去重（管理号唯一，
 *   短窗内重扫必为回声；窗口外允许重扫=动作完成后重新定位同一件）
 * - 反馈三件套：蜂鸣（WebAudio 短音）+ 振动（Android）；接口缺失（iOS Safari/
 *   测试环境）静默降级不抛错
 */

export interface ScanGateOptions {
  /** 任意两次接受的最小间隔（ms），默认 800。 */
  cooldownMs?: number
  /** 同一管理号重复接受的封锁窗（ms），默认 3000。 */
  sameCodeMs?: number
}

export interface ScanGate {
  /** true=放行本次解码；false=冷却/回声期丢弃。 */
  accept(code: string): boolean
  /** 动作完成后手动解锁同码重扫（确认卡关闭即视为一次扫码会话结束）。 */
  reset(): void
}

export function createScanGate(options: ScanGateOptions = {}): ScanGate {
  const cooldownMs = options.cooldownMs ?? 800
  const sameCodeMs = options.sameCodeMs ?? 3000
  let lastCode: string | null = null
  let lastAt = -Infinity

  return {
    accept(code: string): boolean {
      const now = Date.now()
      const since = now - lastAt
      // 冷却窗对任意码生效；同码窗更长（锚定该码上次被接受的时刻，每次接受都刷新——
      // 第二次合法重扫后的慢回声同样落在窗内被拦）
      if (since < cooldownMs) {
        return false
      }
      if (code === lastCode && since < sameCodeMs) {
        return false
      }
      lastCode = code
      lastAt = now
      return true
    },
    reset(): void {
      lastCode = null
      lastAt = -Infinity
    },
  }
}

/** 命中提示音（880Hz/80ms）；AudioContext 不可用时静默。 */
export function beep(): void {
  try {
    const Ctor: typeof AudioContext | undefined =
      globalThis.AudioContext ??
      (globalThis as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
    if (!Ctor) {
      return
    }
    const ctx = new Ctor()
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.frequency.value = 880
    osc.connect(gain)
    gain.connect(ctx.destination)
    osc.start()
    osc.stop(ctx.currentTime + 0.08)
    osc.onended = () => void ctx.close()
  } catch {
    // 音频被系统策略拦截（未交互先播等）——扫码主流程不受影响
  }
}

/** 振动反馈（Android Chrome）；不支持/被拒时静默。 */
export function vibrate(): void {
  try {
    navigator.vibrate?.(50)
  } catch {
    // iOS Safari 无 vibrate——静默降级
  }
}
