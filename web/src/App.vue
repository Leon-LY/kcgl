<script setup lang="ts">
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
</script>

<template>
  <div class="app">
    <header class="app-header">
      <span class="app-title">{{ t('common.appTitle') }}</span>
    </header>
    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>

<!-- 全局基线样式（非 scoped）。设计 token（双 shell 各一份 CSS variables）M1 落地；
     字体栈按 docs/01 7.8：Hiragino(iOS/mac) + Yu Gothic UI/Meiryo(Windows) + Noto Sans CJK JP(Android) -->
<style>
:root {
  --kcgl-color-text: #1f2329;
  --kcgl-color-text-sub: #5f6672;
  --kcgl-color-border: #dcdfe6;
  --kcgl-color-bg: #f5f6f7;
  --kcgl-color-primary: #2062a6;
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

.app {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.app-header {
  background: #fff;
  border-bottom: 1px solid var(--kcgl-color-border);
  padding: 0 16px;
  height: 52px;
  display: flex;
  align-items: center;
}

.app-title {
  font-size: 1.05rem;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.app-main {
  flex: 1;
  width: 100%;
  max-width: 1080px;
  margin: 0 auto;
  padding: 16px;
}
</style>
