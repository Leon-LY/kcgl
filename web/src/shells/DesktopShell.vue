<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LangSwitch from '@/components/LangSwitch.vue'

/**
 * 桌面壳（M1 骨架）：顶栏 = 标题 + 导航 + 用户名 + 语言切换 + 登出，
 * 内容区居中限宽。导航按角色过滤（会场=E+，价格档位/年代号=管理员）；
 * 页面数增多后再升级侧菜单与全局搜索（D-027/D-040）。
 */
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

interface NavItem {
  name: string
  labelKey: string
  /** 可见角色上限（null=全员）；越权访问由路由守卫+服务端 403 双兜底 */
  roles: number[] | null
}

const NAV_ITEMS: NavItem[] = [
  { name: 'home', labelKey: 'nav.home', roles: null },
  { name: 'print', labelKey: 'nav.print', roles: null },
  { name: 'admin-venues', labelKey: 'nav.venues', roles: [1, 2] },
  { name: 'admin-price-bands', labelKey: 'nav.priceBands', roles: [1] },
  { name: 'admin-year-codes', labelKey: 'nav.yearCodes', roles: [1] },
]

const visibleNav = computed(() =>
  NAV_ITEMS.filter((item) => item.roles === null || (auth.me !== null && item.roles.includes(auth.me.role))),
)

function isActive(name: string): boolean {
  return route.name === name
}

async function onLogout(): Promise<void> {
  await auth.logout()
  await router.push({ name: 'login' })
}
</script>

<template>
  <div class="shell shell-desktop">
    <header class="shell-header">
      <div class="shell-header-left">
        <span class="shell-title">{{ t('common.appTitle') }}</span>
        <nav
          v-if="auth.me"
          class="shell-nav"
          :aria-label="t('nav.label')"
        >
          <button
            v-for="item in visibleNav"
            :key="item.name"
            type="button"
            class="shell-nav-link"
            :class="{ 'is-active': isActive(item.name) }"
            :aria-current="isActive(item.name) ? 'page' : undefined"
            @click="router.push({ name: item.name })"
          >
            {{ t(item.labelKey) }}
          </button>
        </nav>
      </div>
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
  background: var(--kcgl-color-card);
  border-bottom: 1px solid var(--kcgl-color-border);
  height: 52px;
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.shell-header-left {
  display: flex;
  align-items: center;
  gap: 24px;
  min-width: 0;
}

.shell-title {
  font-size: 1.05rem;
  font-weight: 600;
  letter-spacing: 0.02em;
  white-space: nowrap;
}

.shell-nav {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  overflow-x: auto;
}

.shell-nav-link {
  height: 32px;
  padding: 0 12px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--kcgl-color-text-sub);
  font: inherit;
  font-size: 0.9rem;
  white-space: nowrap;
  cursor: pointer;
}

.shell-nav-link:hover {
  color: var(--kcgl-color-text);
  background: var(--kcgl-color-bg);
}

.shell-nav-link.is-active {
  color: var(--kcgl-color-primary);
  background: var(--kcgl-color-primary-bg);
  font-weight: 600;
}

.shell-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
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
  background: var(--kcgl-color-card);
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
