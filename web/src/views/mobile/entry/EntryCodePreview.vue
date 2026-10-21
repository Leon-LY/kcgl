<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useDictsStore } from '@/stores/dicts'
import { previewItemCode } from '@/utils/api'
import { toDisplayMessage } from '@/utils/errors'

/**
 * 管理号（確定番号）：可手填（D-167），空欄＝自動採番。
 *
 * 从 EntryForm 抽出来的（D-148）。三个输入全是 props，表单值经 v-model:manual 回传。
 * 空欄时占位符显示服务端预览的自动号（随会场 / 落札日 / 单价防抖重取）；
 * 一旦填写，就地校验「格式 / 会场段 / 月段」，不合法的错误文案直接展示，
 * 父组件提交前读 manualError 拦截（服务端仍会再校验一遍，前端只为少一次往返）。
 * 重录（reEntry）不允许指定管理号——服务端拒绝，故 manualEnabled=false 时退回只读展示。
 */

const props = withDefaults(
  defineProps<{
    venueId: number | null
    buyDate: string
    price: number | null
    manualEnabled?: boolean
  }>(),
  { manualEnabled: true },
)

/** 手填管理号；空串=自动。父组件保存时随 CreateItemPayload.manualCode 上送。 */
const manual = defineModel<string>('manual', { default: '' })

const { t } = useI18n()
const dicts = useDictsStore()

/** 档位是否命中：没命中的价码服务端不发行号，本地也就别去问（省一次往返）。 */
const band = computed(() => dicts.matchBand(props.price))

const PREVIEW_DEBOUNCE_MS = 300

const previewCode = ref<string | null>(null)
const previewError = ref<string | null>(null)
const previewLoading = ref(false)
let previewTimer: ReturnType<typeof setTimeout> | null = null
let previewSeq = 0

const previewable = computed(
  () =>
    props.venueId != null &&
    props.buyDate !== '' &&
    props.price != null &&
    props.price >= 1 &&
    band.value != null,
)

watch([() => props.venueId, () => props.buyDate, () => props.price], () => {
  if (previewTimer != null) {
    clearTimeout(previewTimer)
  }
  previewCode.value = null
  previewError.value = null
  if (!previewable.value) {
    return
  }
  previewTimer = setTimeout(runPreview, PREVIEW_DEBOUNCE_MS)
})

async function runPreview(): Promise<void> {
  if (!previewable.value || props.venueId == null || props.price == null) {
    return
  }
  const seq = ++previewSeq
  previewLoading.value = true
  try {
    const result = await previewItemCode(props.venueId, props.buyDate, props.price)
    if (seq !== previewSeq) {
      return // 已有更新的输入，丢弃过期响应
    }
    previewCode.value = result.code
  } catch (error) {
    if (seq !== previewSeq) {
      return
    }
    previewError.value = toDisplayMessage(error, t)
  } finally {
    if (seq === previewSeq) {
      previewLoading.value = false
    }
  }
}

onBeforeUnmount(() => {
  if (previewTimer != null) {
    clearTimeout(previewTimer)
  }
})

// ------------------------------------------------------------------ 手填校验

/** 与后端 ItemCodeFormatter.CODE_PATTERN 同形（会场 1-8 位 + 月 2 位补零）。 */
const MANUAL_CODE_PATTERN = /^([A-Z0-9]{1,8})(0[1-9]|1[0-2])-([A-Z]{1,3})([1-9][0-9]?)([A-Z])?$/

function isManualFilled(): boolean {
  return manual.value.trim() !== ''
}

/** 失焦时统一转大写（与存储/管理号一致），空白保持空白。 */
function normalizeManual(): void {
  if (isManualFilled()) {
    manual.value = manual.value.trim().toUpperCase()
  }
}

/** 手填号的就地校验：null=没填或合法。父组件提交前用它拦截。 */
const manualError = computed<string | null>(() => {
  if (!isManualFilled()) {
    return null
  }
  // 会场/落札日还没选时无法判断一致性，交给表单必填规则拦
  if (props.venueId == null || props.buyDate === '') {
    return null
  }
  const normalized = manual.value.trim().toUpperCase()
  const match = MANUAL_CODE_PATTERN.exec(normalized)
  if (match == null) {
    return t('entry.manualCodeFormat')
  }
  const venue = dicts.enabledVenues.find((v) => v.id === props.venueId)
  if (venue == null || match[1] !== venue.code) {
    return t('entry.manualCodeVenue', { code: venue?.code ?? '' })
  }
  const month = Number(props.buyDate.slice(5, 7))
  if (Number(match[2]) !== month) {
    return t('entry.manualCodeMonth', { month })
  }
  return null
})

defineExpose({ manualError })
</script>

<template>
  <div class="entry-preview">
    <div
      v-if="!manualEnabled"
      class="entry-preview-row"
    >
      <span class="entry-preview-label">{{ t('entry.nextCode') }}</span>
      <span
        v-if="previewLoading"
        class="entry-preview-code"
      >…</span>
      <span
        v-else-if="previewCode"
        class="entry-preview-code"
      >{{ previewCode }}</span>
    </div>

    <template v-else>
      <label
        class="entry-preview-label"
        for="entry-manual-code"
      >{{ t('entry.nextCode') }}</label>
      <div class="entry-preview-inputrow">
        <input
          id="entry-manual-code"
          v-model="manual"
          class="entry-preview-input"
          type="text"
          maxlength="32"
          autocomplete="off"
          autocapitalize="characters"
          spellcheck="false"
          :placeholder="previewLoading ? '…' : (previewCode ?? '')"
          :aria-invalid="manualError != null"
          @blur="normalizeManual"
        >
        <button
          v-if="isManualFilled()"
          type="button"
          class="entry-preview-reset"
          @click="manual = ''"
        >
          {{ t('entry.manualCodeReset') }}
        </button>
      </div>
      <p
        v-if="manualError"
        class="entry-preview-error"
        role="alert"
      >
        {{ manualError }}
      </p>
    </template>

    <p
      v-if="previewError && !isManualFilled()"
      class="entry-preview-error"
    >
      {{ previewError }}
    </p>
    <p class="entry-preview-note">
      {{ manualEnabled && isManualFilled() ? t('entry.manualCodeNote') : t('entry.nextCodeNote') }}
    </p>
  </div>
</template>

<style scoped>
.entry-preview {
  margin: 0 16px;
  padding: 12px 16px;
  border: 1px dashed var(--kcgl-color-border);
  border-radius: 6px;
  background: #fff;
  display: grid;
  gap: 6px;
}

.entry-preview-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.entry-preview-label {
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.entry-preview-code {
  font-size: 1.3rem;
  font-weight: 700;
  letter-spacing: 0.04em;
  font-family: 'Courier New', monospace;
}

.entry-preview-inputrow {
  display: flex;
  align-items: center;
  gap: 8px;
}

.entry-preview-input {
  flex: 1;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  font-size: 1.15rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  font-family: 'Courier New', monospace;
  color: var(--kcgl-color-text);
  background: var(--kcgl-color-bg);
}

.entry-preview-input:focus {
  border-color: var(--kcgl-color-primary);
  outline: none;
}

.entry-preview-input::placeholder {
  color: var(--kcgl-color-text-faint);
  font-weight: 500;
}

.entry-preview-reset {
  flex-shrink: 0;
  padding: 4px 8px;
  border: none;
  background: none;
  color: var(--kcgl-color-primary);
  font-size: 0.85rem;
  cursor: pointer;
}

.entry-preview-error {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-danger);
}

.entry-preview-note {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}
</style>
