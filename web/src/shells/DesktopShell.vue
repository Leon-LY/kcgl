<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useShell } from '@/composables/useShell'
import LangSwitch from '@/components/LangSwitch.vue'
import PwaInstallBar from '@/components/PwaInstallBar.vue'

/**
 * 桌面壳（M1 骨架 → M5-③ 侧边栏化，D-040 触发：导航链接达 9 个）：
 * 左侧栏 = 系统名 + 分组导航（運営/管理/監視）+ 底部切回移动壳；顶栏 = 用户名 +
 * 语言切换 + 登出。导航按角色过滤（会场=E+，价格档位/账号/设置/監視组=管理员）；
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
  {
    // 監視（M5-④）：治理与排障三页——台帳/操作日志/システム状況，全部仅管理员
    labelKey: 'nav.groupMonitor',
    items: [
      { name: 'ledgers', labelKey: 'nav.ledgers', roles: [1] },
      { name: 'admin-logs', labelKey: 'nav.operationLogs', roles: [1] },
      { name: 'admin-system', labelKey: 'nav.system', roles: [1] },
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
    <!-- 游客态（登录页/会话过期）不渲染侧栏：导航项全按角色过滤，未登录时
         只剩一条 208px 空白栏——用户会误判成加载失败。侧栏出现时机与顶栏
         用户名/登出一致（都挂在 auth.me 上）。 -->
    <aside
      v-if="auth.me"
      class="shell-sidebar"
    >
      <div class="shell-sidebar-top">
        <span class="shell-title">{{ t('common.appTitle') }}</span>
      </div>
      <nav
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
        <PwaInstallBar warning-only />
        <!-- 换页淡入 4px：表达"换了页"而非编排式入场；out-in 避免两页并置
             （时长取 --kcgl-dur-fast，两段合计 240ms，仍在 250ms 预算内）。
             Transition 的直接子节点必须是**单个元素**：Excel／印刷两页的根是
             <el-config-provider>（渲染出 fragment 根），Vue 无法给它做离场，
             out-in 会卡在"旧页已离场、新页还没入场"，右侧整片空白且刷新才恢复
             （D-104）。故包一层无语义挂载点。Key 取路由 path 而非 fullPath：
             筛选条件走 query 变化，不该把整页重挂载。插槽属性改名 viewRoute，
             避开外层 useRoute() 的 route（no-template-shadow，同对象两处绑定易读错）。 -->
        <RouterView v-slot="{ Component, route: viewRoute }">
          <Transition
            name="kcgl-view"
            mode="out-in"
          >
            <div
              :key="viewRoute.path"
              class="kcgl-view-slot"
            >
              <component :is="Component" />
            </div>
          </Transition>
        </RouterView>
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
  display: flex;
}

/* 侧栏=chrome 层（深墨蓝），与内容面刻意分离：导航是骨架不是数据。
   v1 通体白面 + 仅靠右边框分隔，导航与内容读成同一层，整屏"没有设计"的观感来源。 */
.shell-sidebar {
  position: sticky;
  top: 0;
  height: 100vh;
  width: 216px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: var(--kcgl-space-5);
  padding: var(--kcgl-space-4) var(--kcgl-space-3) var(--kcgl-space-5);
  background: var(--kcgl-ink-900);
  overflow-y: auto;
  scrollbar-width: thin;
}

.shell-sidebar-top {
  padding: 0 var(--kcgl-space-2) var(--kcgl-space-4);
  border-bottom: 1px solid var(--kcgl-ink-line);
}

.shell-title {
  font-size: 1rem;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--kcgl-ink-text);
  white-space: nowrap;
}

.shell-nav {
  display: flex;
  flex-direction: column;
  gap: var(--kcgl-space-5);
  min-height: 0;
}

.shell-nav-group {
  display: grid;
  gap: 2px;
}

/* 分组标题：小字距 + 降透明度，做"分组标签"而非"条目"（在深底上靠明度分层） */
.shell-nav-group-title {
  margin: 0 0 var(--kcgl-space-2);
  padding: 0 var(--kcgl-space-2);
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--kcgl-ink-text-dim);
}

.shell-nav-link {
  height: 36px;
  padding: 0 var(--kcgl-space-3);
  border: none;
  border-radius: var(--kcgl-radius-s);
  background: transparent;
  color: var(--kcgl-ink-text-dim);
  font: inherit;
  font-size: 0.9rem;
  text-align: left;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background-color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    color var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

.shell-nav-link:hover {
  color: var(--kcgl-ink-text);
  background: var(--kcgl-ink-800);
}

/* 选中态：整块染色 + 加亮字重（不用左侧彩条——docs/07 §1 明令禁止侧描边装饰） */
.shell-nav-link.is-active {
  color: #fff;
  background: var(--kcgl-ink-700);
  font-weight: 600;
}

.shell-sidebar-bottom {
  margin-top: auto;
  padding: 0 var(--kcgl-space-1);
}

.shell-mobile-switch {
  width: 100%;
  height: 36px;
  border: 1px solid var(--kcgl-ink-line);
  border-radius: var(--kcgl-radius-s);
  background: transparent;
  color: var(--kcgl-ink-text-dim);
  font-size: 0.82rem;
  cursor: pointer;
  transition:
    border-color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    color var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

.shell-mobile-switch:hover {
  color: var(--kcgl-ink-text);
  border-color: var(--kcgl-ink-text-dim);
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
  height: 56px;
  padding: 0 var(--kcgl-space-6);
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--kcgl-space-4);
}

.shell-actions {
  display: flex;
  align-items: center;
  gap: var(--kcgl-space-3);
  flex-shrink: 0;
}

.shell-user {
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.shell-logout {
  height: 32px;
  padding: 0 var(--kcgl-space-3);
  border: 1px solid var(--kcgl-color-border);
  border-radius: var(--kcgl-radius-s);
  background: var(--kcgl-color-card);
  color: var(--kcgl-color-text-sub);
  font-size: 0.85rem;
  cursor: pointer;
  transition:
    border-color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    color var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

.shell-logout:hover {
  color: var(--kcgl-color-danger);
  border-color: var(--kcgl-color-danger-border);
}

/* 内容容器：1280 上限（docs/07 §4 v2 由 1080 放宽——桌面主任务是读表，
   1080 在 1440 屏上左右各留 180px 空白，列表列数被迫压缩） */
.shell-main {
  flex: 1;
  width: 100%;
  max-width: 1280px;
  margin: 0 auto;
  padding: var(--kcgl-space-5) var(--kcgl-space-6) var(--kcgl-space-7);
}

@media (max-width: 720px) {
  /* 窄屏兜底：桌面壳被强制使用（UA/偏好）时侧栏收窄保内容可用 */
  .shell-sidebar {
    width: 64px;
    padding: var(--kcgl-space-4) 6px;
  }

  .shell-nav-group-title,
  .shell-mobile-switch {
    display: none;
  }

  .shell-title {
    font-size: 0.85rem;
  }

  .shell-main {
    padding: var(--kcgl-space-4);
  }
}
</style>
