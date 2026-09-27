<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LangSwitch from '@/components/LangSwitch.vue'

/**
 * 桌面壳（M1 骨架）：顶栏 = 标题 + 用户名 + 语言切换 + 登出，
 * 内容区居中限宽。M2 接入 Element Plus 后补侧菜单与全局搜索（D-027）。
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
  <div class="shell">
    <header class="shell-header">
      <span class="shell-title">{{ t('common.appTitle') }}</span>
      <span class="shell-actions">
        <span
          v-if="auth.me"
          class="shell-user"
        >{{ auth.me.displayName }}</span>
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
  background: #fff;
  border-bottom: 1px solid var(--kcgl-color-border);
  height: 52px;
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.shell-title {
  font-size: 1.05rem;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.shell-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.shell-user {
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.shell-logout {
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: #fff;
  color: var(--kcgl-color-text-sub);
  font-size: 0.85rem;
  cursor: pointer;
}

.shell-logout:hover {
  color: var(--kcgl-color-text);
  border-color: var(--kcgl-color-text-sub);
}

.shell-main {
  flex: 1;
  width: 100%;
  max-width: 1080px;
  margin: 0 auto;
  padding: 24px;
}
</style>
