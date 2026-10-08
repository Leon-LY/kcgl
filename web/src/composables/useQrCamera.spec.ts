import { afterEach, describe, expect, it } from 'vitest'
import { defineComponent, h } from 'vue'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { i18n } from '@/i18n'
import { CAMERA_CONSTRAINTS, FORMATS, useQrCamera } from './useQrCamera'

enableAutoUnmount(afterEach)

/**
 * 摄像头共用件（D-149）：状态迁移 + 摄像头错误名 → 三语文案的映射。
 * 真实取流（getUserMedia）由真机矩阵覆盖（docs/qa/device-matrix.md），这里只钉
 * 状态机与文案映射——扫码页与盘点页同用一件，行为须与各自原先那份一致。
 */

const Host = defineComponent({
  setup() {
    return { camera: useQrCamera() }
  },
  render: () => h('div'),
})

type Camera = ReturnType<typeof useQrCamera>

function mountCamera(): Camera {
  const host = mount(Host, { global: { plugins: [i18n] } })
  return (host.vm as unknown as { camera: Camera }).camera
}

describe('useQrCamera', () => {
  it('starts not ready, torch hidden, no error message', () => {
    const camera = mountCamera()
    expect(camera.cameraReady.value).toBe(false)
    expect(camera.torchSupported.value).toBe(false)
    expect(camera.torchOn.value).toBe(false)
    expect(camera.cameraErrorMessage.value).toBe('')
  })

  it('camera-on with the torch capability marks the torch button supported and clears a previous error', () => {
    const camera = mountCamera()
    camera.onCameraError(new Error('oops')) // name='Error' → 通用失败文案，随后被 camera-on 清掉
    camera.onCameraOn({ torch: true })
    expect(camera.cameraReady.value).toBe(true)
    expect(camera.torchSupported.value).toBe(true)
    expect(camera.cameraErrorMessage.value).toBe('')
  })

  it('camera-on without the torch bit keeps the torch button hidden', () => {
    const camera = mountCamera()
    camera.onCameraOn({ zoom: {} })
    expect(camera.cameraReady.value).toBe(true)
    expect(camera.torchSupported.value).toBe(false)
  })

  it('maps NotAllowedError and PermissionDeniedError to the denied copy', () => {
    for (const name of ['NotAllowedError', 'PermissionDeniedError']) {
      const camera = mountCamera()
      const error = new Error('denied')
      error.name = name
      camera.onCameraError(error)
      expect(camera.cameraReady.value).toBe(false)
      expect(camera.cameraErrorMessage.value).toBe(i18n.global.t('scan.cameraDenied'))
    }
  })

  it('maps InsecureContextError to the insecure-context copy (http 下取流被浏览器拒绝)', () => {
    const camera = mountCamera()
    const error = new Error('insecure')
    error.name = 'InsecureContextError'
    camera.onCameraError(error)
    expect(camera.cameraErrorMessage.value).toBe(i18n.global.t('scan.cameraInsecure'))
  })

  it('maps any other error name to the generic failure copy', () => {
    const camera = mountCamera()
    const error = new Error('boom')
    error.name = 'NotReadableError'
    camera.onCameraError(error)
    expect(camera.cameraErrorMessage.value).toBe(i18n.global.t('scan.cameraFailed'))
  })

  it('camera-off resets readiness, torch support and the torch switch', () => {
    const camera = mountCamera()
    camera.onCameraOn({ torch: true })
    camera.toggleTorch()
    camera.onCameraOff()
    expect(camera.cameraReady.value).toBe(false)
    expect(camera.torchSupported.value).toBe(false)
    expect(camera.torchOn.value).toBe(false)
  })

  it('toggleTorch flips the switch both ways', () => {
    const camera = mountCamera()
    camera.toggleTorch()
    expect(camera.torchOn.value).toBe(true)
    camera.toggleTorch()
    expect(camera.torchOn.value).toBe(false)
  })

  it('exposes the rear-camera constraints and the QR-only format set', () => {
    expect(CAMERA_CONSTRAINTS).toEqual({ facingMode: 'environment' })
    expect(FORMATS).toEqual(['qr_code'])
  })
})
