<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LangSwitch from '@/components/LangSwitch.vue'
import PwaInstallBar from '@/components/PwaInstallBar.vue'

/**
 * 移动壳：顶栏 = 标题 + 语言切换；底栏 = van-tabbar 五页导航
 * （首页/商品登録/入庫確認/スキャン/本日；录入页仅编辑者以上显示，
 * 路由守卫与服务端 403 双兜底）。登录/改密页不显示底栏。
 * 顶上挂 PwaInstallBar（M6-①：安装引导/未送信警示）；standalone 键盘弹起时
 * visualViewport 收缩——把焦点输入滚回可视区（iOS PWA 专项，docs/01 iOS 节）。
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

// standalone 下 iOS 键盘遮内容的兜底：视口收缩时聚焦输入滚回可视区
function onViewportResize(): void {
  const active = document.activeElement
  if (active instanceof HTMLElement && active.matches('input, textarea, select')) {
    active.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }
}

onMounted(() => {
  window.visualViewport?.addEventListener('resize', onViewportResize)
})

onBeforeUnmount(() => {
  window.visualViewport?.removeEventListener('resize', onViewportResize)
})
</script>

<template>
  <div class="shell shell-mobile">
    <PwaInstallBar />
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
      <!-- 与桌面壳同一换页动效（见 DesktopShell） -->
      <RouterView v-slot="{ Component }">
        <Transition
          name="kcgl-view"
          mode="out-in"
        >
          <component :is="Component" />
        </Transition>
      </RouterView>
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
        to="/scan"
        icon="scan"
      >
        {{ t('nav.scan') }}
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

/* 顶栏=chrome 层（深墨底），与桌面侧栏同一层：移动端顶部一条深色带在
   仓库强光下也稳得住边界，且与内容面（白卡/浅底）拉开层次。 */
.shell-header {
  position: sticky;
  top: 0;
  z-index: 10;
  background: var(--kcgl-ink-900);
  box-shadow: var(--kcgl-shadow-chrome);
  height: 52px;
  padding: 0 var(--kcgl-space-4);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--kcgl-space-3);
}

.shell-title {
  font-size: 1rem;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--kcgl-ink-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.shell-actions {
  display: flex;
  align-items: center;
  gap: var(--kcgl-space-2);
  flex-shrink: 0;
}

.shell-logout {
  height: 32px;
  padding: 0 var(--kcgl-space-3);
  border: 1px solid var(--kcgl-ink-line);
  border-radius: var(--kcgl-radius-s);
  background: transparent;
  color: var(--kcgl-ink-text-dim);
  font-size: 0.85rem;
  cursor: pointer;
  transition:
    border-color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    color var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

.shell-logout:active {
  color: var(--kcgl-ink-text);
  border-color: var(--kcgl-ink-text-dim);
}

.shell-main {
  flex: 1;
  width: 100%;
  /* 列向 flex：让登录页等"占满剩余高度"的页面用 min-height:100% 落位，
     不再靠 calc(100vh - 52px) 之类硬编码——安装引导条一出现就失准 */
  display: flex;
  flex-direction: column;
  padding: var(--kcgl-space-4);
  padding-bottom: calc(var(--kcgl-space-4) + env(safe-area-inset-bottom));
}

/* 底栏固定悬浮：内容区让出 tabbar 高度 + 安全区，防最后一屏被遮 */
.shell-main.has-tabbar {
  padding-bottom: calc(72px + env(safe-area-inset-bottom));
}
</style>
