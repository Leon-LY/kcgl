<script setup lang="ts">
import { computed, watchEffect } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import MobileShell from './shells/MobileShell.vue'
import DesktopShell from './shells/DesktopShell.vue'
import { useShell } from '@/composables/useShell'
import { useSse } from '@/composables/useSse'

const { shell } = useShell()
// 实时同步单例挂载（M3-③）：会话联动建连/回前台重建/粗粒度失效分发
useSse()
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

<!-- 全局样式（设计令牌/UI 库主题桥接/共享基元）统一在 src/styles/brand.css，main.ts 引入 -->
