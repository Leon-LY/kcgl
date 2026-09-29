import { computed, reactive } from 'vue'

/**
 * 主屏安装引导状态（M6-①，docs/01 7.5/R1/R2）：「主屏安装=数据安全前提而非
 * 可选 UX」——未加主屏的 Safari 标签页 ITP 7 天无交互即清空 IndexedDB
 * （WebKit 行为，主屏豁免）。
 *
 * - standalone 检测双通道：display-mode 媒体查询（Android/桌面）+
 *   navigator.standalone（iOS Safari 专有）；安装后媒体查询 change 即时翻转。
 * - beforeinstallprompt（Chrome/Android）：拦截系统安装横幅改为自有引导按钮，
 *   调 prompt() 触发原生安装对话框；iOS 不触发该事件→展示手动步骤文案。
 * - 引导可「後で」关闭（localStorage 记忆）；警示条（队列非空且未安装）
 *   不可关闭——数据风险存续期间持续呈现。
 */

/** TS DOM 标准库未收录的 beforeinstallprompt 事件最小结构。 */
interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

const GUIDE_STORAGE_KEY = 'kcgl-pwa-guide-dismissed'

const state = reactive({
  standalone: false,
  /** beforeinstallprompt 已捕获（可弹原生安装框：Android/桌面 Chrome）。 */
  canPrompt: false,
  guideDismissed: false,
})

let initialized = false
let deferredPrompt: BeforeInstallPromptEvent | null = null

/** iOS Safari 的 standalone 专有标志（display-mode 媒体查询在 iOS 上不适用）。 */
function navigatorStandalone(): boolean {
  return (navigator as Navigator & { standalone?: boolean }).standalone === true
}

/** iOS 检测（含 iPadOS 13+ 的 Mac 型 UA——maxTouchPoints 触屏数兜底）。 */
function detectIos(): boolean {
  const ua = navigator.userAgent
  if (/iphone|ipod/i.test(ua)) {
    return true
  }
  if (/ipad/i.test(ua)) {
    return true
  }
  return /macintosh/i.test(ua) && navigator.maxTouchPoints > 1
}

function init(): void {
  if (initialized) {
    return
  }
  initialized = true

  state.guideDismissed = localStorage.getItem(GUIDE_STORAGE_KEY) === '1'
  state.standalone = navigatorStandalone()

  // matchMedia 防御（旧浏览器/jsdom 无实现）：navigator.standalone 已兜底 iOS
  if (typeof window.matchMedia === 'function') {
    const media = window.matchMedia('(display-mode: standalone)')
    state.standalone = media.matches || state.standalone
    media.addEventListener('change', (event) => {
      state.standalone = event.matches || navigatorStandalone()
    })
  }

  window.addEventListener('beforeinstallprompt', (event) => {
    event.preventDefault()
    deferredPrompt = event as BeforeInstallPromptEvent
    state.canPrompt = true
  })
}

function dismissGuide(): void {
  localStorage.setItem(GUIDE_STORAGE_KEY, '1')
  state.guideDismissed = true
}

/** 触发原生安装对话框（仅 canPrompt 时有意义）；返回用户选择结果。 */
async function promptInstall(): Promise<'accepted' | 'dismissed' | 'unavailable'> {
  if (deferredPrompt == null) {
    return 'unavailable'
  }
  await deferredPrompt.prompt()
  const choice = await deferredPrompt.userChoice
  deferredPrompt = null
  state.canPrompt = false
  return choice.outcome
}

/** 测试隔离：复位内存态（localStorage 由各 spec 自行清理）。 */
function resetForTests(): void {
  initialized = false
  deferredPrompt = null
  state.standalone = false
  state.canPrompt = false
  state.guideDismissed = false
}

const isIos = detectIos()

export function usePwaInstall() {
  const shouldShowGuide = computed(() => !state.standalone && !state.guideDismissed)

  return {
    state,
    isIos,
    shouldShowGuide,
    init,
    dismissGuide,
    promptInstall,
    resetForTests,
  }
}
