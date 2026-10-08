<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useDictsStore } from '@/stores/dicts'
import { previewItemCode } from '@/utils/api'
import { toDisplayMessage } from '@/utils/errors'

/**
 * 管理号（確定番号）预览：只读，随会场 / 落札日 / 单价变化防抖重取一次。
 *
 * 从 EntryForm 抽出来的（D-148）。三个输入全是 props，自己不持有表单状态、也不向父组件
 * 回传任何东西——预览只是「保存时会是什么号」的提示，号本身在保存时由服务端定（文案里
 * 明示了「≠保留」）。
 */

const props = defineProps<{ venueId: number | null; buyDate: string; price: number | null }>()

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
</script>

<template>
  <div class="entry-preview">
    <div class="entry-preview-row">
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
    <p
      v-if="previewError"
      class="entry-preview-error"
    >
      {{ previewError }}
    </p>
    <p class="entry-preview-note">
      {{ t('entry.nextCodeNote') }}
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
  gap: 4px;
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
