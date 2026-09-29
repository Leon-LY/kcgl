import 'fake-indexeddb/auto'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

import PwaInstallBar from './PwaInstallBar.vue'
import { db } from '@/db/dexie'
import { usePwaInstall } from '@/composables/usePwaInstall'
import { useUploadQueue } from '@/composables/useUploadQueue'
import { i18n } from '@/i18n'

/**
 * 主屏安装引导条（M6-①）：guide 可关闭引导 / warning 队列非空持续警示 /
 * warningOnly（桌面壳只警示不引导）/ standalone 全隐藏。
 * 队列计数在组件 init 时刷新——种子数据一律 mount 前落库。
 */

enableAutoUnmount(() => {})

const pwa = usePwaInstall()
const queue = useUploadQueue()

function stubMatchMedia(matches: boolean): void {
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockImplementation((query: string) => ({
      matches,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  )
}

/** 落一条退避中的待传照片（不触发实际上传）。 */
async function seedPending(): Promise<void> {
  await db.uploadQueue.add({
    clientUuid: 'req-bar-1',
    itemId: 5,
    data: new Uint8Array([1]).buffer,
    mimeType: 'image/jpeg',
    status: 'pending',
    attempts: 0,
    nextRetryAt: Date.now() + 60_000,
    createdAt: Date.now(),
  })
}

function mountBar(props: { warningOnly?: boolean } = {}) {
  setActivePinia(createPinia())
  return mount(PwaInstallBar, {
    props,
    global: { plugins: [i18n] },
  })
}

beforeEach(async () => {
  vi.unstubAllGlobals()
  localStorage.clear()
  pwa.resetForTests()
  await queue.resetForTests()
  i18n.global.locale.value = 'ja-JP'
})

describe('PwaInstallBar (M6-1)', () => {
  it('renders the dismissible install guide in a plain browser tab', async () => {
    const wrapper = mountBar()

    const bar = wrapper.find('[data-testid="pwa-bar"]')
    expect(bar.exists()).toBe(true)
    expect(bar.classes()).toContain('is-guide')
    expect(bar.text()).toContain('ホーム画面に追加')
    expect(wrapper.find('.pwa-bar-dismiss').exists()).toBe(true)

    await wrapper.find('.pwa-bar-dismiss').trigger('click')
    expect(wrapper.find('[data-testid="pwa-bar"]').exists()).toBe(false)
    expect(localStorage.getItem('kcgl-pwa-guide-dismissed')).toBe('1')
  })

  it('renders the non-dismissible warning while photos are pending and not installed', async () => {
    await seedPending()
    const wrapper = mountBar()
    // 组件 init 的 refreshCounts 走 IndexedDB 异步事件——轮询等计数落账、mode 翻 warning
    await vi.waitFor(() => {
      expect(wrapper.find('[data-testid="pwa-bar"]').classes()).toContain('is-warning')
    })
    expect(wrapper.find('[data-testid="pwa-bar"]').text()).toContain('1枚が未送信')
    // 数据风险存续期间不可关闭
    expect(wrapper.find('.pwa-bar-dismiss').exists()).toBe(false)
  })

  it('hides the guide when warningOnly is set (desktop shell: warning only)', () => {
    const wrapper = mountBar({ warningOnly: true })
    expect(wrapper.find('[data-testid="pwa-bar"]').exists()).toBe(false)
  })

  it('renders nothing once running standalone (installed to home screen)', () => {
    stubMatchMedia(true)
    pwa.init()
    const wrapper = mountBar()
    expect(wrapper.find('[data-testid="pwa-bar"]').exists()).toBe(false)
  })
})
