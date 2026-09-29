<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useShell } from '@/composables/useShell'
import LangSwitch from '@/components/LangSwitch.vue'

/**
 * 桌面壳（M1 骨架 → M5-③ 侧边栏化，D-040 触发：导航链接达 9 个）：
 * 左侧栏 = 系统名 + 分组导航（運営/管理）+ 底部切回移动壳；顶栏 = 用户名 +
 * 语言切换 + 登出。导航按角色过滤（会场=E+，价格档位/账号/设置=管理员）；
 * 桌面壳不再展示移动首页——'/' 落地大盘（D-072），切壳入口由侧栏底部承担。
 * .shell-nav-link 类名是 E2E 稳定契约（admin.spec 链接计数），重构保持不变。
 */

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { switchShell } = useShell()

interface NavItem {
  name: string
  labelKey: string
  /** 可见角色上限（null=全员）；越权访问由路由守卫+服务端 403 双兜底 */
  roles: number[] | null
}

interface NavGroup {
  labelKey: string
  items: NavItem[]
}

const NAV_GROUPS: NavGroup[] = [
  {
    labelKey: 'nav.groupOps',
    items: [
      { name: 'dashboard', labelKey: 'nav.dashboard', roles: null },
      { name: 'items', labelKey: 'nav.items', roles: null },
      { name: 'yahoo', labelKey: 'nav.yahoo', roles: null },
      { name: 'excel', labelKey: 'nav.excel', roles: null },
      { name: 'print', labelKey: 'nav.print', roles: null },
    ],
  },
  {
    labelKey: 'nav.groupAdmin',
    items: [
      { name: 'admin-venues', labelKey: 'nav.venues', roles: [1, 2] },
      { name: 'admin-price-bands', labelKey: 'nav.priceBands', roles: [1] },
      { name: 'admin-users', labelKey: 'nav.users', roles: [1] },
      { name: 'admin-settings', labelKey: 'nav.settings', roles: [1] },
    ],
  },
]

const visibleGroups = computed(() =>
  NAV_GROUPS.map((group) => ({
    ...group,
    items: group.items.filter(
      (item) => item.roles === null || (auth.me !== null && item.roles.includes(auth.me.role)),
    ),
  })).filter((group) => group.items.length > 0),
)

function isActive(name: string): boolean {
  return route.name === name
}

async function onLogout(): Promise<void> {
  await auth.logout()
  await router.push({ name: 'login' })
}

/** 切回移动壳：'/' 在移动壳渲染 HomeView（桌面壳下它重定向到大盘）。 */
function onSwitchMobile(): void {
  switchShell('mobile')
  void router.push({ name: 'home' })
}
</script>

<template>
  <div class="shell shell-desktop">
    <aside class="shell-sidebar">
      <div class="shell-sidebar-top">
        <span class="shell-title">{{ t('common.appTitle') }}</span>
      </div>
      <nav
        v-if="auth.me"
        class="shell-nav"
        :aria-label="t('nav.label')"
      >
        <section
          v-for="group in visibleGroups"
          :key="group.labelKey"
          class="shell-nav-group"
        >
          <h2 class="shell-nav-group-title">
            {{ t(group.labelKey) }}
          </h2>
          <button
            v-for="item in group.items"
            :key="item.name"
            type="button"
            class="shell-nav-link"
            :class="{ 'is-active': isActive(item.name) }"
            :aria-current="isActive(item.name) ? 'page' : undefined"
            @click="router.push({ name: item.name })"
          >
            {{ t(item.labelKey) }}
          </button>
        </section>
      </nav>
      <div class="shell-sidebar-bottom">
        <button
          v-if="auth.me"
          type="button"
          class="shell-mobile-switch"
          @click="onSwitchMobile"
        >
          {{ t('common.shellMobile') }}
        </button>
      </div>
    </aside>
    <div class="shell-body">
      <header class="shell-header">
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
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
  display: flex;
}

.shell-sidebar {
  position: sticky;
  top: 0;
  height: 100vh;
  width: 208px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px 12px;
  background: var(--kcgl-color-card);
  border-right: 1px solid var(--kcgl-color-border);
  overflow-y: auto;
}

.shell-sidebar-top {
  padding: 0 8px;
}

.shell-title {
  font-size: 1.05rem;
  font-weight: 600;
  letter-spacing: 0.02em;
  white-space: nowrap;
}

.shell-nav {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
}

.shell-nav-group {
  display: grid;
  gap: 2px;
}

.shell-nav-group-title {
  margin: 0 0 6px;
  padding: 0 8px;
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--kcgl-color-text-sub);
}

.shell-nav-link {
  height: 34px;
  padding: 0 10px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--kcgl-color-text-sub);
  font: inherit;
  font-size: 0.9rem;
  text-align: left;
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

.shell-sidebar-bottom {
  margin-top: auto;
  padding: 0 4px;
}

.shell-mobile-switch {
  width: 100%;
  height: 34px;
  border: 1px dashed var(--kcgl-color-border);
  border-radius: 4px;
  background: transparent;
  color: var(--kcgl-color-text-sub);
  font-size: 0.82rem;
  cursor: pointer;
}

.shell-mobile-switch:hover {
  color: var(--kcgl-color-text);
  border-color: var(--kcgl-color-text-sub);
}

.shell-body {
  flex: 1;
  min-width: 0;
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
  justify-content: flex-end;
  gap: 16px;
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

@media (max-width: 720px) {
  /* 窄屏兜底：桌面壳被强制使用（UA/偏好）时侧栏收窄保内容可用 */
  .shell-sidebar {
    width: 64px;
    padding: 16px 6px;
  }

  .shell-nav-group-title,
  .shell-mobile-switch {
    display: none;
  }

  .shell-title {
    font-size: 0.85rem;
  }
}
</style>
