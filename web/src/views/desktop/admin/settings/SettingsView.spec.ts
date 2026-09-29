import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const apiMocks = vi.hoisted(() => ({
  fetchSettings: vi.fn(),
  updateSetting: vi.fn(),
}))

// ApiError/errors 保持真实实现；仅替换网络端点
vi.mock('@/utils/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/api')>()
  return {
    ...actual,
    fetchSettings: apiMocks.fetchSettings,
    updateSetting: apiMocks.updateSetting,
  }
})

import SettingsView from './SettingsView.vue'
import { i18n } from '@/i18n'
import { useSettingsStore } from '@/stores/settings'
import { ApiError, type SettingsData } from '@/utils/api'

/**
 * 系统设置页（M5-③）：快照载入表单 → 客户端校验（范围/黄红关系/custom 尺寸）
 * → 按序 PUT（阈值双键排序保证中间态合法）→ 回包即快照 + 回写共享缓存。
 */

const INITIAL: SettingsData = {
  warnDays: 30,
  alarmDays: 90,
  labelPreset: 'small',
  labelWidthMm: 50,
  labelHeightMm: 30,
}

/** 慢阈值双键 PUT 后的目标快照。 */
const SLOW_TARGET: SettingsData = { ...INITIAL, warnDays: 45, alarmDays: 100 }

/** 标签规格改 custom 后的目标快照。 */
const LABEL_TARGET: SettingsData = { ...INITIAL, labelPreset: 'custom', labelWidthMm: 60, labelHeightMm: 40 }

async function mountView(): Promise<VueWrapper> {
  const wrapper = mount(SettingsView, {
    global: { plugins: [i18n] },
  })
  await flushPromises()
  return wrapper
}

/** 第 N 张卡片（0=滞销阈值，1=标签规格）。 */
function card(wrapper: VueWrapper, index: 0 | 1): VueWrapper {
  return wrapper.findAll('.settings-card')[index]!
}

function slowInputs(wrapper: VueWrapper): Array<VueWrapper> {
  return card(wrapper, 0).findAll('.settings-input')
}

enableAutoUnmount(afterEach)

beforeEach(() => {
  vi.resetAllMocks()
  setActivePinia(createPinia())
  i18n.global.locale.value = 'ja-JP'
  apiMocks.fetchSettings.mockResolvedValue(INITIAL)
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('snapshot load', () => {
  it('populates the form from GET /api/settings', async () => {
    const wrapper = await mountView()

    const [warn, alarm] = slowInputs(wrapper)
    expect((warn.element as HTMLInputElement).value).toBe('30')
    expect((alarm.element as HTMLInputElement).value).toBe('90')
    expect(card(wrapper, 1).find('.settings-preset-option.is-active').text()).toContain('38×21')
    // 非 custom：尺寸输入不出现
    expect(card(wrapper, 1).findAll('.settings-input')).toHaveLength(0)
  })

  it('shows the load-failure state and recovers via retry', async () => {
    apiMocks.fetchSettings.mockRejectedValue(new Error('network down'))
    const wrapper = await mountView()

    expect(wrapper.find('.settings-error').text()).toBe('設定の読み込みに失敗しました')

    apiMocks.fetchSettings.mockResolvedValue(INITIAL)
    await wrapper.find('.settings-retry').trigger('click')
    await flushPromises()

    expect(apiMocks.fetchSettings).toHaveBeenCalledTimes(2)
    expect((slowInputs(wrapper)[0].element as HTMLInputElement).value).toBe('30')
  })
})

describe('slow-move thresholds', () => {
  it('rejects a non-numeric or out-of-range value without any PUT', async () => {
    const wrapper = await mountView()

    await slowInputs(wrapper)[0].setValue('abc')
    await card(wrapper, 0).find('.settings-save').trigger('click')

    expect(card(wrapper, 0).find('.settings-error').text())
      .toBe('しきい値は 1〜3650 日の範囲で指定してください')
    expect(apiMocks.updateSetting).not.toHaveBeenCalled()
  })

  it('rejects warn >= alarm (yellow must stay below red) without any PUT', async () => {
    const wrapper = await mountView()

    await slowInputs(wrapper)[0].setValue('100')
    await card(wrapper, 0).find('.settings-save').trigger('click')

    expect(card(wrapper, 0).find('.settings-error').text())
      .toBe('黄色しきい値は赤色しきい値より小さくしてください')
    expect(apiMocks.updateSetting).not.toHaveBeenCalled()
  })

  it('PUTs alarm first when the target raises the red threshold (each step stays valid)', async () => {
    apiMocks.updateSetting.mockResolvedValue(SLOW_TARGET)
    const wrapper = await mountView()

    await slowInputs(wrapper)[0].setValue('45')
    await slowInputs(wrapper)[1].setValue('100')
    await card(wrapper, 0).find('.settings-save').trigger('click')
    await flushPromises()

    expect(apiMocks.updateSetting).toHaveBeenNthCalledWith(1, 'slow_move.alarm_days', '100')
    expect(apiMocks.updateSetting).toHaveBeenNthCalledWith(2, 'slow_move.warn_days', '45')
    expect(apiMocks.updateSetting).toHaveBeenCalledTimes(2)
    expect(card(wrapper, 0).find('.settings-saved').text()).toBe('保存しました')
    // 回包快照回填表单（现值已变为 45/100）
    expect((slowInputs(wrapper)[0].element as HTMLInputElement).value).toBe('45')
  })

  it('PUTs warn first when the target lowers the red threshold', async () => {
    const lowered: SettingsData = { ...INITIAL, warnDays: 20, alarmDays: 40 }
    apiMocks.updateSetting.mockResolvedValue(lowered)
    const wrapper = await mountView()

    await slowInputs(wrapper)[0].setValue('20')
    await slowInputs(wrapper)[1].setValue('40')
    await card(wrapper, 0).find('.settings-save').trigger('click')
    await flushPromises()

    expect(apiMocks.updateSetting).toHaveBeenNthCalledWith(1, 'slow_move.warn_days', '20')
    expect(apiMocks.updateSetting).toHaveBeenNthCalledWith(2, 'slow_move.alarm_days', '40')
  })

  it('surfaces the server rejection (400017) on the section', async () => {
    apiMocks.updateSetting.mockRejectedValue(
      new ApiError(400017, '設定値が正しくありません', 'err-0017'),
    )
    const wrapper = await mountView()

    await slowInputs(wrapper)[0].setValue('45')
    await card(wrapper, 0).find('.settings-save').trigger('click')
    await flushPromises()

    expect(card(wrapper, 0).find('.settings-error').text()).toBe('設定値が正しくありません')
  })
})

describe('label preset', () => {
  function selectPreset(wrapper: VueWrapper, label: string): Promise<void> {
    const option = card(wrapper, 1).findAll('.settings-preset-option').find((o) => o.text().includes(label))!
    return option.find('input[type="radio"]').setValue(true) as Promise<void>
  }

  it('switching to custom reveals dimension inputs, validates range, and PUTs preset→width→height', async () => {
    apiMocks.updateSetting.mockResolvedValue(LABEL_TARGET)
    const wrapper = await mountView()

    await selectPreset(wrapper, 'カスタム')
    const inputs = card(wrapper, 1).findAll('.settings-input')
    expect(inputs).toHaveLength(2)
    expect((inputs[0].element as HTMLInputElement).value).toBe('50')

    // 越界（幅 29 < 30）→ 拒绝，不 PUT
    await inputs[0].setValue('29')
    await card(wrapper, 1).find('.settings-save').trigger('click')
    expect(card(wrapper, 1).find('.settings-error').text())
      .toBe('ラベル幅は 30〜100 mm の範囲で指定してください')
    expect(apiMocks.updateSetting).not.toHaveBeenCalled()

    // 合法（60×40）→ 三键按序 PUT
    await inputs[0].setValue('60')
    await inputs[1].setValue('40')
    await card(wrapper, 1).find('.settings-save').trigger('click')
    await flushPromises()

    expect(apiMocks.updateSetting).toHaveBeenNthCalledWith(1, 'label.preset', 'custom')
    expect(apiMocks.updateSetting).toHaveBeenNthCalledWith(2, 'label.width', '60')
    expect(apiMocks.updateSetting).toHaveBeenNthCalledWith(3, 'label.height', '40')
    expect(card(wrapper, 1).find('.settings-saved').text()).toBe('保存しました')
  })

  it('saving a non-custom preset PUTs the preset key only (dims untouched)', async () => {
    apiMocks.updateSetting.mockResolvedValue({ ...INITIAL, labelPreset: 'medium' })
    const wrapper = await mountView()

    await selectPreset(wrapper, '50×30')
    await card(wrapper, 1).find('.settings-save').trigger('click')
    await flushPromises()

    expect(apiMocks.updateSetting).toHaveBeenCalledTimes(1)
    expect(apiMocks.updateSetting).toHaveBeenCalledWith('label.preset', 'medium')
  })
})

describe('shared settings cache write-back', () => {
  it('updates the resident store snapshot after a save when the cache is warm (print-page default)', async () => {
    const store = useSettingsStore()
    store.$patch({ data: INITIAL, loaded: true })
    apiMocks.updateSetting.mockResolvedValue(SLOW_TARGET)
    const wrapper = await mountView()

    await slowInputs(wrapper)[0].setValue('45')
    await card(wrapper, 0).find('.settings-save').trigger('click')
    await flushPromises()

    expect(store.data).toEqual(SLOW_TARGET)
  })

  it('does not fabricate a cold cache (store stays empty until its own load)', async () => {
    apiMocks.updateSetting.mockResolvedValue(SLOW_TARGET)
    const wrapper = await mountView()

    await slowInputs(wrapper)[0].setValue('45')
    await card(wrapper, 0).find('.settings-save').trigger('click')
    await flushPromises()

    const store = useSettingsStore()
    expect(store.data).toBeNull()
    expect(store.loaded).toBe(false)
  })
})
