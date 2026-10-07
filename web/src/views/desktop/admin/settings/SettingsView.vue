<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useSettingsStore } from '@/stores/settings'
import AppPageHeader from '@/components/AppPageHeader.vue'
import { fetchSettings, updateSetting, type SettingsData } from '@/utils/api'
import { toDisplayMessage } from '@/utils/errors'

/**
 * 系统设置页（/admin/settings，M5-③，仅管理员）：滞销黄/红阈值 + 标签规格
 * （预置面四选一；custom 时幅/高 mm）。写路径按键 PUT（值未变=服务端幂等
 * 无操作），阈值双键存在跨字段关系（warn<alarm），两键同存时按
 * 「目标 alarm 抬高 → 先 alarm 后 warn，否则先 warn 后 alarm」排序，
 * 保证每个中间态都满足关系（证明见 D-072）。PUT 回包即最新快照，同时
 * 回写 settings store——打印页默认规格与 admin 页同会话联动
 * （自己的广播回声被 D-070 抑制，须主动回写）。
 */

const DAYS_MIN = 1
const DAYS_MAX = 3650
const WIDTH_MIN = 30
const WIDTH_MAX = 100
const HEIGHT_MIN = 21
const HEIGHT_MAX = 60

const PRESET_KEYS = ['small', 'medium', 'large', 'custom'] as const
type PresetKey = (typeof PRESET_KEYS)[number]

const { t } = useI18n()
const settingsStore = useSettingsStore()

// ------------------------------------------------------------- 读取

/** 服务器现值快照（PUT 回包同步更新）；null=未加载。 */
const snapshot = ref<SettingsData | null>(null)
const loading = ref(true)
const loadError = ref(false)

// v-model 在 type="number" 输入框上会自动把合法数字转成 number（Vue 内建行为），
// 表单值因此是 string | number 的联合
const formWarn = ref<string | number>('')
const formAlarm = ref<string | number>('')
const formPreset = ref<PresetKey>('small')
const formWidth = ref<string | number>('')
const formHeight = ref<string | number>('')

function syncForm(next: SettingsData): void {
  snapshot.value = next
  // 数值字段直接存 number（与 v-model 在 type="number" 上的自动转型一致）：
  // 回填后与用户输入等值，watch 不触发，保存成功提示得以保留
  formWarn.value = next.warnDays
  formAlarm.value = next.alarmDays
  formPreset.value = next.labelPreset
  formWidth.value = next.labelWidthMm
  formHeight.value = next.labelHeightMm
}

async function load(): Promise<void> {
  loading.value = true
  loadError.value = false
  try {
    syncForm(await fetchSettings())
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

// ------------------------------------------------------------- 滞销阈值

const slowSaving = ref(false)
const slowError = ref('')
const slowSaved = ref(false)

watch([formWarn, formAlarm], () => {
  slowSaved.value = false
})

/** 纯数字且在界内才放行（与后端 requireDays 同口径）。 */
function parseDaysInput(value: string | number): number | null {
  const trimmed = String(value).trim()
  if (!/^\d+$/.test(trimmed)) {
    return null
  }
  const days = Number(trimmed)
  return days >= DAYS_MIN && days <= DAYS_MAX ? days : null
}

function validateSlow(): string {
  const warn = parseDaysInput(formWarn.value)
  const alarm = parseDaysInput(formAlarm.value)
  if (warn === null || alarm === null) {
    return t('settings.errDaysRange', { min: DAYS_MIN, max: DAYS_MAX })
  }
  if (warn >= alarm) {
    return t('settings.errWarnLtAlarm')
  }
  return ''
}

/** PUT 回包即全量快照：回写本页并同步常驻缓存（打印页默认规格）。 */
function applySnapshot(next: SettingsData): void {
  syncForm(next)
  if (settingsStore.data !== null) {
    settingsStore.data = next
  }
}

async function saveSlow(): Promise<void> {
  if (slowSaving.value || snapshot.value === null) {
    return
  }
  slowError.value = validateSlow()
  if (slowError.value !== '') {
    return
  }
  const warn = parseDaysInput(formWarn.value)!
  const alarm = parseDaysInput(formAlarm.value)!
  // 排序证明（每步中间态都满足 warn<alarm）：
  // alarm 抬高时先 alarm：currentWarn<currentAlarm<targetAlarm，再 warn 已过校验；
  // 否则先 warn：targetWarn<targetAlarm≤currentAlarm，再 alarm 已过校验。
  const puts: Array<[string, string]> = alarm > snapshot.value.alarmDays
    ? [
        ['slow_move.alarm_days', String(alarm)],
        ['slow_move.warn_days', String(warn)],
      ]
    : [
        ['slow_move.warn_days', String(warn)],
        ['slow_move.alarm_days', String(alarm)],
      ]
  slowSaving.value = true
  try {
    let next: SettingsData | null = null
    for (const [key, value] of puts) {
      next = await updateSetting(key, value)
    }
    applySnapshot(next!)
    slowSaved.value = true
  } catch (error) {
    slowError.value = toDisplayMessage(error, t)
  } finally {
    slowSaving.value = false
  }
}

// ------------------------------------------------------------- 标签规格

const labelSaving = ref(false)
const labelError = ref('')
const labelSaved = ref(false)

watch([formPreset, formWidth, formHeight], () => {
  labelSaved.value = false
})

function parseRangeInput(value: string | number, min: number, max: number): number | null {
  const trimmed = String(value).trim()
  if (!/^\d+$/.test(trimmed)) {
    return null
  }
  const parsed = Number(trimmed)
  return parsed >= min && parsed <= max ? parsed : null
}

function validateLabel(): string {
  if (formPreset.value !== 'custom') {
    return ''
  }
  if (parseRangeInput(formWidth.value, WIDTH_MIN, WIDTH_MAX) === null) {
    return t('settings.errWidthRange', { min: WIDTH_MIN, max: WIDTH_MAX })
  }
  if (parseRangeInput(formHeight.value, HEIGHT_MIN, HEIGHT_MAX) === null) {
    return t('settings.errHeightRange', { min: HEIGHT_MIN, max: HEIGHT_MAX })
  }
  return ''
}

async function saveLabel(): Promise<void> {
  if (labelSaving.value || snapshot.value === null) {
    return
  }
  labelError.value = validateLabel()
  if (labelError.value !== '') {
    return
  }
  // 幅/高仅在 custom 时写库：切回预置面保留既有自定义尺寸（下次选 custom 即复用）
  const puts: Array<[string, string]> = [['label.preset', formPreset.value]]
  if (formPreset.value === 'custom') {
    puts.push(
      ['label.width', String(parseRangeInput(formWidth.value, WIDTH_MIN, WIDTH_MAX)!)],
      ['label.height', String(parseRangeInput(formHeight.value, HEIGHT_MIN, HEIGHT_MAX)!)],
    )
  }
  labelSaving.value = true
  try {
    let next: SettingsData | null = null
    for (const [key, value] of puts) {
      next = await updateSetting(key, value)
    }
    applySnapshot(next!)
    labelSaved.value = true
  } catch (error) {
    labelError.value = toDisplayMessage(error, t)
  } finally {
    labelSaving.value = false
  }
}
</script>

<template>
  <section class="settings-view">
    <AppPageHeader :title="t('settings.title')" />

    <div
      v-if="loadError"
      class="kcgl-card settings-card"
    >
      <p class="settings-error">
        {{ t('settings.loadFailed') }}
      </p>
      <button
        type="button"
        class="settings-retry"
        :disabled="loading"
        @click="load"
      >
        {{ t('common.reload') }}
      </button>
    </div>

    <template v-else>
      <div class="kcgl-card settings-card">
        <h2 class="settings-card-title">
          {{ t('settings.slowTitle') }}
        </h2>
        <p class="settings-note">
          {{ t('settings.slowNote') }}
        </p>
        <div class="settings-fields">
          <label class="settings-field">
            <span class="settings-field-label">{{ t('settings.warnDays') }}</span>
            <input
              v-model="formWarn"
              type="number"
              :min="DAYS_MIN"
              :max="DAYS_MAX"
              class="settings-input"
            >
          </label>
          <label class="settings-field">
            <span class="settings-field-label">{{ t('settings.alarmDays') }}</span>
            <input
              v-model="formAlarm"
              type="number"
              :min="DAYS_MIN"
              :max="DAYS_MAX"
              class="settings-input"
            >
          </label>
        </div>
        <p
          v-if="slowError"
          class="settings-error"
        >
          {{ slowError }}
        </p>
        <p
          v-else-if="slowSaved"
          class="settings-saved"
        >
          {{ t('settings.saved') }}
        </p>
        <div class="settings-actions">
          <button
            type="button"
            class="settings-save"
            :disabled="snapshot === null || slowSaving"
            @click="saveSlow"
          >
            {{ slowSaving ? t('common.saving') : t('common.save') }}
          </button>
        </div>
      </div>

      <div class="kcgl-card settings-card">
        <h2 class="settings-card-title">
          {{ t('settings.labelTitle') }}
        </h2>
        <p class="settings-note">
          {{ t('settings.labelNote') }}
        </p>
        <div class="settings-preset-options">
          <label
            v-for="option in PRESET_KEYS"
            :key="option"
            class="settings-preset-option"
            :class="{ 'is-active': formPreset === option }"
          >
            <input
              v-model="formPreset"
              type="radio"
              :value="option"
              class="settings-preset-radio"
            >
            <span>{{ t(`print.preset.${option}`) }}</span>
          </label>
        </div>
        <div
          v-if="formPreset === 'custom'"
          class="settings-fields"
        >
          <label class="settings-field">
            <span class="settings-field-label">{{ t('settings.labelWidth') }}</span>
            <input
              v-model="formWidth"
              type="number"
              :min="WIDTH_MIN"
              :max="WIDTH_MAX"
              class="settings-input"
            >
            <span class="settings-unit">mm</span>
          </label>
          <label class="settings-field">
            <span class="settings-field-label">{{ t('settings.labelHeight') }}</span>
            <input
              v-model="formHeight"
              type="number"
              :min="HEIGHT_MIN"
              :max="HEIGHT_MAX"
              class="settings-input"
            >
            <span class="settings-unit">mm</span>
          </label>
        </div>
        <p
          v-if="labelError"
          class="settings-error"
        >
          {{ labelError }}
        </p>
        <p
          v-else-if="labelSaved"
          class="settings-saved"
        >
          {{ t('settings.saved') }}
        </p>
        <div class="settings-actions">
          <button
            type="button"
            class="settings-save"
            :disabled="snapshot === null || labelSaving"
            @click="saveLabel"
          >
            {{ labelSaving ? t('common.saving') : t('common.save') }}
          </button>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.settings-view {
  display: grid;
  gap: 16px;
  max-width: 720px;
}

.settings-card {
  display: grid;
  gap: 12px;
  padding: 20px;
  justify-items: start;
}

.settings-card-title {
  margin: 0;
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--kcgl-color-text-sub);
}

.settings-note {
  margin: 0;
  font-size: 0.85rem;
  line-height: 1.6;
  color: var(--kcgl-color-text-sub);
}

.settings-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
}

.settings-field {
  display: grid;
  gap: 6px;
}

.settings-field-label {
  font-size: 0.8rem;
  color: var(--kcgl-color-text-sub);
}

.settings-input {
  width: 120px;
  height: 36px;
  padding: 0 10px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: #fff;
  color: var(--kcgl-color-text);
  font: inherit;
  font-size: 0.95rem;
}

.settings-unit {
  font-size: 0.8rem;
  color: var(--kcgl-color-text-sub);
}

.settings-preset-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.settings-preset-option {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 36px;
  padding: 0 14px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: #fff;
  font-size: 0.9rem;
  cursor: pointer;
}

.settings-preset-option.is-active {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
  font-weight: 600;
}

.settings-preset-radio {
  /* 视觉由外壳承担，保留可聚焦单选语义 */
  margin: 0;
  accent-color: var(--kcgl-color-primary);
}

.settings-error {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-danger);
}

.settings-saved {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-primary);
}

.settings-actions {
  display: flex;
  gap: 8px;
}

.settings-save,
.settings-retry {
  height: 36px;
  padding: 0 16px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: #fff;
  color: var(--kcgl-color-text);
  font-size: 0.9rem;
  cursor: pointer;
}

.settings-save:hover:not(:disabled),
.settings-retry:hover:not(:disabled) {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
}

.settings-save:disabled,
.settings-retry:disabled {
  opacity: 0.6;
  cursor: default;
}
</style>
