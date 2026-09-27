<script setup lang="ts">
import { computed, watchEffect } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import MobileShell from './shells/MobileShell.vue'
import DesktopShell from './shells/DesktopShell.vue'
import { useShell } from '@/composables/useShell'

const { shell } = useShell()
const activeShell = computed(() => (shell.value === 'mobile' ? MobileShell : DesktopShell))

// 三语联动：切换语言时，除组件文案外，文档标题与 <html lang> 同步切换。
// 标题规则：子页面「页面名｜系统名」（titleWithPage，分隔符按语言习惯），首页仅系统名。
const { t, locale } = useI18n()
const route = useRoute()
watchEffect(() => {
  document.documentElement.lang = locale.value
  const titleKey = route.meta.titleKey ?? 'common.appTitle'
  document.title =
    titleKey === 'common.appTitle'
      ? t('common.appTitle')
      : t('common.titleWithPage', { page: t(titleKey), app: t('common.appTitle') })
})
</script>

<template>
  <component :is="activeShell" />
</template>

<!-- 全局基线样式（非 scoped）：设计 token + 共享表单/卡片基元（自绘，D-027）。
     字体栈按 docs/01 7.8：Hiragino(iOS/mac) + Yu Gothic UI/Meiryo(Windows) + Noto Sans CJK JP(Android) -->
<style>
:root {
  --kcgl-color-text: #1f2329;
  --kcgl-color-text-sub: #5f6672;
  --kcgl-color-border: #dcdfe6;
  --kcgl-color-bg: #f5f6f7;
  --kcgl-color-primary: #2062a6;
  --kcgl-color-primary-dark: #1b5290;
  --kcgl-color-danger: #b3261e;
  --kcgl-color-danger-bg: #fdeceb;
  --kcgl-color-danger-border: #f2c4c1;
  --kcgl-color-info-text: #1b5290;
  --kcgl-color-info-bg: #eaf2fa;
  --kcgl-color-info-border: #c5d9ee;
  --kcgl-color-success: #2e7d32;
  font-size: 16px;
}

* {
  box-sizing: border-box;
}

html,
body {
  margin: 0;
  padding: 0;
}

body {
  font-family:
    Hiragino Kaku Gothic ProN,
    'Hiragino Sans',
    'Yu Gothic UI',
    YuGothic,
    Meiryo,
    'Noto Sans CJK JP',
    sans-serif;
  color: var(--kcgl-color-text);
  background: var(--kcgl-color-bg);
  line-height: 1.6;
  -webkit-font-smoothing: antialiased;
}

/* ---- 共享基元（双壳通用，M2 起 UI 库组件与这些类并存） ---- */

.kcgl-card {
  background: #fff;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 6px;
  padding: 28px;
}

.kcgl-field {
  display: grid;
  gap: 4px;
}

.kcgl-label {
  font-size: 0.85rem;
  color: var(--kcgl-color-text-sub);
}

.kcgl-input {
  width: 100%;
  height: 44px;
  padding: 0 12px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  font: inherit;
  font-size: 1rem; /* 16px：防止 iOS Safari 聚焦自动放大 */
  color: var(--kcgl-color-text);
  background: #fff;
}

.kcgl-input:focus {
  outline: none;
  border-color: var(--kcgl-color-primary);
}

.kcgl-btn {
  height: 44px;
  padding: 0 16px;
  border: none;
  border-radius: 4px;
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}

.kcgl-btn:disabled {
  opacity: 0.6;
  cursor: default;
}

.kcgl-btn-primary {
  background: var(--kcgl-color-primary);
  color: #fff;
}

.kcgl-btn-primary:hover:not(:disabled) {
  background: var(--kcgl-color-primary-dark);
}

.kcgl-btn-block {
  width: 100%;
}

.kcgl-error-box {
  padding: 10px 12px;
  border: 1px solid var(--kcgl-color-danger-border);
  border-radius: 4px;
  background: var(--kcgl-color-danger-bg);
  color: var(--kcgl-color-danger);
  font-size: 0.9rem;
}

.kcgl-info-box {
  padding: 10px 12px;
  border: 1px solid var(--kcgl-color-info-border);
  border-radius: 4px;
  background: var(--kcgl-color-info-bg);
  color: var(--kcgl-color-info-text);
  font-size: 0.9rem;
}
</style>
