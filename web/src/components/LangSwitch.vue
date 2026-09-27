<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'

const { t, locale } = useI18n()
const auth = useAuthStore()

// 原生 select：键盘可达、无需 UI 库（D-027），三语标签用各自母语显示
const options = [
  { value: 'ja-JP', label: '日本語' },
  { value: 'zh-CN', label: '中文' },
  { value: 'en-US', label: 'English' },
]

async function onChange(event: Event): Promise<void> {
  const selected = (event.target as HTMLSelectElement).value
  await auth.setLocale(selected)
}
</script>

<template>
  <select
    class="lang-switch"
    :value="locale"
    :aria-label="t('common.language')"
    @change="onChange"
  >
    <option
      v-for="option in options"
      :key="option.value"
      :value="option.value"
    >
      {{ option.label }}
    </option>
  </select>
</template>

<style scoped>
.lang-switch {
  height: 32px;
  padding: 0 8px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: var(--kcgl-color-card);
  color: var(--kcgl-color-text);
  font-size: 0.85rem;
  cursor: pointer;
}
</style>
