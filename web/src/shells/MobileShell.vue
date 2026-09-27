<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LangSwitch from '@/components/LangSwitch.vue'

/**
 * 移动壳：顶栏 = 标题 + 语言切换；底栏 = van-tabbar 四页导航
 * （M2-8b：首页/商品登録/入庫確認/本日；录入页仅编辑者以上显示，
 * 路由守卫与服务端 403 双兜底）。登录/改密页不显示底栏。
 */
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const showTabbar = computed(
  () =>
    auth.me !== null &&
    route.name !== 'login' &&
    route.name !== 'change-password',
)
const canEntry = computed(() => auth.me !== null && auth.me.role <= 2)

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
    <main
      class="shell-main"
      :class="{ 'has-tabbar': showTabbar }"
    >
      <RouterView />
    </main>
    <van-tabbar
      v-if="showTabbar"
      route
      :placeholder="false"
      :safe-area-inset-bottom="true"
    >
      <van-tabbar-item
        to="/"
        icon="home-o"
      >
        {{ t('nav.home') }}
      </van-tabbar-item>
      <van-tabbar-item
        v-if="canEntry"
        to="/entry"
        icon="edit"
      >
        {{ t('nav.entry') }}
      </van-tabbar-item>
      <van-tabbar-item
        to="/arrival"
        icon="logistics"
      >
        {{ t('nav.arrival') }}
      </van-tabbar-item>
      <van-tabbar-item
        to="/today"
        icon="notes-o"
      >
        {{ t('nav.today') }}
      </van-tabbar-item>
    </van-tabbar>
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

/* 底栏固定悬浮：内容区让出 tabbar 高度 + 安全区，防最后一屏被遮 */
.shell-main.has-tabbar {
  padding-bottom: calc(66px + env(safe-area-inset-bottom));
}
</style>
