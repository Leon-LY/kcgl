import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { BarcodeFormat } from 'vue-qrcode-reader'

/**
 * QR 连续取流的共用件（扫码页 ScanView 与盘点会话页 StocktakeScanView 同用一套，D-149）：
 * 取流约束、只解 QR 的格式集、火炬开关、摄像头错误名 → 三语提示的映射。
 *
 * 这两页原先各抄一份逐字相同的脚本。留在各页自持的只有 cameraPaused：暂停的诱因不同
 * （扫码页看动作弹层、盘点页看 close/cancel 弹层），不属于取流本身。
 */

/** 后置摄像头；桌面开发无此设备时由浏览器回落到默认设备。 */
export const CAMERA_CONSTRAINTS = { facingMode: 'environment' }
/** 只解 QR：管理号是 QR，放开一维码会把货架条码一并读进来。 */
export const FORMATS: BarcodeFormat[] = ['qr_code']

export function useQrCamera() {
  const { t } = useI18n()

  const cameraReady = ref(false)
  const torchOn = ref(false)
  const torchSupported = ref(false)
  const cameraErrorName = ref('')

  /**
   * camera-on 载荷为 Partial<MediaTrackCapabilities>；TS DOM lib 未收录非标准的
   * torch 能力位，且 .vue script 下 no-undef 不识别纯类型名——按 object 收参
   * （对组件事件签名逆变兼容）后结构化收窄读取（真机由 vue-qrcode-reader 回填）。
   */
  function onCameraOn(capabilities: object): void {
    cameraReady.value = true
    torchSupported.value = (capabilities as { torch?: boolean }).torch === true
    cameraErrorName.value = ''
  }

  function onCameraOff(): void {
    cameraReady.value = false
    torchSupported.value = false
    torchOn.value = false
  }

  function onCameraError(error: Error): void {
    cameraReady.value = false
    cameraErrorName.value = error.name
  }

  /**
   * 火炬开关。状态归本件持有，页面不再直接写 `torchOn = !torchOn`——解构出来的引用
   * 在模板里作赋值目标时编译器无法确定它是 ref（读可以，写不行），故写操作收进来。
   */
  function toggleTorch(): void {
    torchOn.value = !torchOn.value
  }

  const cameraErrorMessage = computed(() => {
    switch (cameraErrorName.value) {
      case '':
        return ''
      case 'NotAllowedError':
      case 'PermissionDeniedError':
        return t('scan.cameraDenied')
      case 'InsecureContextError':
        return t('scan.cameraInsecure')
      default:
        return t('scan.cameraFailed')
    }
  })

  return {
    cameraReady,
    torchOn,
    torchSupported,
    cameraErrorMessage,
    onCameraOn,
    onCameraOff,
    onCameraError,
    toggleTorch,
  }
}
