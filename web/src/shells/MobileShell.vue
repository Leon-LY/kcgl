<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LangSwitch from '@/components/LangSwitch.vue'

/**
 * 移动壳（M1 骨架）：顶栏 = 标题 + 语言切换；M2 接入 Vant 后补
 * van-tabbar 导航与安全区适配。无 UI 库（D-027：登录/骨架页自绘，
 * Vant 随 M2 首个消费页引入）。
 */
const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

async function onLogout(): Promise<void> {
  await auth.logout()
  await router.push({ name: 'login' })
}
</script>

<template>
  <div class="shell shell-mobile">
    <header class="shell-header">
      <span class="shell-title">{{ t('common.appTitle') }}</span>
      <span class="shell-actions">
        <LangSwitch />
        <button
          v-if="auth.me"
          type="button"
          class="shell-logout"
          @click="onLogout"
        >
          {{ t('common.logout') }}
        </button>
      </span>
    </header>
    <main class="shell-main">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.shell-header {
  position: sticky;
  top: 0;
  z-index: 10;
  background: var(--kcgl-color-card);
  border-bottom: 1px solid var(--kcgl-color-border);
  height: 52px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.shell-title {
  font-size: 1.05rem;
  font-weight: 600;
  letter-spacing: 0.02em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.shell-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.shell-logout {
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: var(--kcgl-color-card);
  color: var(--kcgl-color-text-sub);
  font-size: 0.85rem;
  cursor: pointer;
}

.shell-main {
  flex: 1;
  width: 100%;
  padding: 16px;
  padding-bottom: calc(16px + env(safe-area-inset-bottom));
}
</style>
