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
      <!-- 与桌面壳同一换页动效（含"必须包一层单元素挂载点"的缘由，见 DesktopShell） -->
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
  /* 100vh 在 iOS Safari 上比可视区高（地址栏/工具条），又因为 viewport-fit=cover
     而在 PWA 下量到含安全区的整屏；dvh 跟随实际可视高度。旧写法留着做兜底
     （不支持 dvh 的老 WebView 仍拿到 100vh），支持的浏览器以后一条覆盖。 */
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
}

/* 底栏净高（Vant 默认 --van-tabbar-height: 50px）提升为壳级变量：
   页面自带的固定底条（如 ArrivalView 的操作条）要按同一数字让开底栏，
   否则会盖住导航、把用户困在页内。安全区由底栏自己吃，页面不必重复加。 */
.shell-mobile {
  --kcgl-tabbar-height: 50px;
  /* 移动内容列宽：由壳统一定，页面不再各写 max-width（原 9 个页面各写一份 560px，
     平板宽度下就成了"居中一条窄柱 + 大片空白"，读起来是 PC 页面压小了）。
     手机档与旧值同；平板档下面放宽。见 docs/07 §1 三档表。 */
  --kcgl-content-width: 560px;
}

/* 平板档（768–1024，docs/07 §1）：内容列放宽 + 页边距加大，不拉伸成桌面版；
   折叠/小平板（600–767）保持单列手机版面，只是数字键盘区更宽裕，不需单独一档。 */
@media (min-width: 768px) {
  .shell-mobile {
    --kcgl-content-width: 880px;
  }

  .shell-main {
    padding: var(--kcgl-space-5);
  }
}

/* 顶栏=chrome 层（深墨底），与桌面侧栏同一层：移动端顶部一条深色带在
   仓库强光下也稳得住边界，且与内容面（白卡/浅底）拉开层次。
   min-height 而非 height：安全区自带高度，固定高度会把内容压扁（刘海屏 PWA）。 */
.shell-header {
  position: sticky;
  top: 0;
  z-index: 10;
  background: var(--kcgl-ink-900);
  box-shadow: var(--kcgl-shadow-chrome);
  min-height: 52px;
  padding: env(safe-area-inset-top)
    max(var(--kcgl-space-4), env(safe-area-inset-right)) 0
    max(var(--kcgl-space-4), env(safe-area-inset-left));
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

/* 触控目标 ≥44px（docs/07 §1 自查清单）：顶栏两个控件原先 32px，
   在手机上落在拇指热区边缘，误触率高。 */
.shell-logout {
  min-height: 44px;
  padding: 0 var(--kcgl-space-3);
  border: 1px solid var(--kcgl-ink-line);
  border-radius: var(--kcgl-radius-s);
  background: transparent;
  color: var(--kcgl-ink-text-dim);
  font-size: 0.9rem;
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
  padding: var(--kcgl-space-4)
    max(var(--kcgl-space-4), env(safe-area-inset-right)) 0
    max(var(--kcgl-space-4), env(safe-area-inset-left));
  padding-bottom: calc(var(--kcgl-space-4) + env(safe-area-inset-bottom));
}

/* 内容列（唯一限宽点）：页面根容器不再各写 max-width */
.kcgl-view-slot {
  width: 100%;
  max-width: var(--kcgl-content-width);
  align-self: center;
}

/* 底栏固定悬浮：内容区让出 tabbar 高度 + 安全区，防最后一屏被遮 */
.shell-main.has-tabbar {
  padding-bottom: calc(var(--kcgl-tabbar-height) + 22px + env(safe-area-inset-bottom));
}
</style>
