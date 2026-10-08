<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useShell } from '@/composables/useShell'
import SetupChecklistCard from '@/components/SetupChecklistCard.vue'

const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const { shell, switchShell } = useShell()

const canEntry = computed(() => auth.me != null && auth.me.role <= 2)

/**
 * 门禁回跳的落地信息。路由守卫把电脑版专属页（ラベル印刷、/admin/** 等）在
 * 手机壳里的访问拦成 { home, ?desktopOnly=<原路径> }——这里把原路径还原出来，
 * 让用户看到「你点的是哪个页面」以及一条明确的出路，而不是撞一下墙回到原地。
 */
const desktopOnlyPath = computed(() => {
  const raw = route.query.desktopOnly
  const value = Array.isArray(raw) ? raw[0] : raw
  return typeof value === 'string' && value.length > 0 ? value : null
})

/** 原路径的文档标题（读路由 meta，不另抄一份文案表）；解析不到就不带页面名。 */
const desktopOnlyName = computed(() => {
  const path = desktopOnlyPath.value
  if (path === null) {
    return ''
  }
  const titleKey = router.resolve(path).meta.titleKey
  return typeof titleKey === 'string' ? t(titleKey) : ''
})

/** 切到电脑版并直接打开目标页：切换本身会落 localStorage，桌面壳侧栏可切回。 */
function openInDesktop(): void {
  const path = desktopOnlyPath.value
  switchShell('desktop')
  void router.push(path ?? { name: 'dashboard' })
}

const shellOptions = [
  { value: 'mobile', labelKey: 'common.shellMobile' },
  { value: 'desktop', labelKey: 'common.shellDesktop' },
] as const

/**
 * 切到桌面壳后 '/' 会重定向到大盘（M5-③ D-072：桌面落地页=dashboard），
 * 这里主动带上目标页，避免「切了壳还停在移动首页」的一跳。
 */
function onSwitchShell(target: 'mobile' | 'desktop'): void {
  switchShell(target)
  if (target === 'desktop') {
    void router.push({ name: 'dashboard' })
  }
}
</script>

<template>
  <section
    v-if="auth.me"
    class="home"
  >
    <h1 class="home-welcome">
      {{ t('home.welcome', { name: auth.me.displayName }) }}
    </h1>

    <!-- 门禁回跳的说明卡：只在守卫拦回来时出现（?desktopOnly）。role=alert 是因为
         它就是用户刚才那一下的反馈，出现即该被读出来 -->
    <div
      v-if="desktopOnlyPath"
      class="kcgl-card home-notice"
      role="alert"
    >
      <p class="home-notice-text">
        {{
          desktopOnlyName
            ? t('home.desktopOnly.title', { name: desktopOnlyName })
            : t('home.desktopOnly.titlePlain')
        }}
      </p>
      <button
        type="button"
        class="kcgl-btn kcgl-btn-primary home-notice-action"
        @click="openInDesktop"
      >
        {{ t('home.desktopOnly.action') }}
      </button>
    </div>

    <SetupChecklistCard />

    <div class="kcgl-card home-card">
      <h2 class="home-card-title">
        {{ t('home.accountInfo') }}
      </h2>
      <dl class="home-info">
        <div class="home-info-row">
          <dt>{{ t('home.username') }}</dt>
          <dd>{{ auth.me.username }}</dd>
        </div>
        <div class="home-info-row">
          <dt>{{ t('home.displayName') }}</dt>
          <dd>{{ auth.me.displayName }}</dd>
        </div>
        <div class="home-info-row">
          <dt>{{ t('home.role') }}</dt>
          <dd>{{ t(`auth.role.${auth.me.role}`) }}</dd>
        </div>
        <div class="home-info-row">
          <dt>{{ t('home.locale') }}</dt>
          <dd>{{ auth.me.locale }}</dd>
        </div>
      </dl>
    </div>

    <div class="kcgl-card home-card">
      <h2 class="home-card-title">
        {{ t('common.shellSwitch') }}
      </h2>
      <div class="home-shell-options">
        <button
          v-for="option in shellOptions"
          :key="option.value"
          type="button"
          class="home-shell-option"
          :class="{ 'is-active': shell === option.value }"
          :aria-pressed="shell === option.value"
          @click="onSwitchShell(option.value)"
        >
          {{ t(option.labelKey) }}
        </button>
      </div>
    </div>

    <div class="kcgl-card home-card">
      <h2 class="home-card-title">
        {{ t('home.quickActions') }}
      </h2>
      <button
        v-if="canEntry"
        type="button"
        class="home-entry-link"
        @click="router.push({ name: 'entry' })"
      >
        {{ t('home.goEntry') }}
      </button>
      <button
        type="button"
        class="home-entry-link"
        @click="router.push({ name: 'arrival' })"
      >
        {{ t('home.goArrival') }}
      </button>
      <button
        type="button"
        class="home-entry-link"
        @click="router.push({ name: 'scan' })"
      >
        {{ t('home.goScan') }}
      </button>
      <button
        type="button"
        class="home-entry-link"
        @click="router.push({ name: 'today' })"
      >
        {{ t('home.goToday') }}
      </button>
      <!-- 标签印刷是电脑版页面（meta.shell）：先挂个小标，别让用户点进去才被
           守卫拦回来。点仍然可点——拦回来会带一句白话说明和一条出路 -->
      <button
        type="button"
        class="home-entry-link"
        @click="router.push({ name: 'print' })"
      >
        {{ t('home.goPrint') }}
        <span class="kcgl-mini-tag home-entry-tag">{{ t('home.desktopOnly.tag') }}</span>
      </button>
      <button
        type="button"
        class="home-entry-link"
        @click="router.push({ name: 'stocktake' })"
      >
        {{ t('home.goStocktake') }}
      </button>
      <button
        type="button"
        class="home-entry-link"
        @click="router.push({ name: 'pending-shipments' })"
      >
        {{ t('home.goPendingShipments') }}
      </button>
    </div>

    <p class="home-preparing">
      {{ t('home.preparing') }}
    </p>
  </section>
</template>

<style scoped>
.home {
  display: grid;
  gap: 16px;
}

.home-welcome {
  margin: 0;
  font-size: 1.2rem;
  font-weight: 600;
}

.home-card {
  display: grid;
  gap: 12px;
  padding: 20px;
}

/* 门禁回跳说明卡：主色描边标出「你刚才那一下的结果在这里」，形状仍是整框
   （侧边彩色竖条是 docs/07 §1 硬禁用），与同页其他卡同构。 */
.home-notice {
  display: grid;
  gap: 12px;
  padding: 20px;
  border-color: var(--kcgl-color-primary);
}

.home-notice-text {
  margin: 0;
  font-size: 0.95rem;
  line-height: 1.6;
}

.home-notice-action {
  width: 100%;
}

.home-card-title {
  margin: 0;
  font-size: 1rem;
  font-weight: 600;
  color: var(--kcgl-color-text-sub);
}

.home-info {
  margin: 0;
  display: grid;
  gap: 8px;
}

/* 手机档：账号字段改为「标签在上、值在下」的单列（原生移动版式）；
   600px 起才恢复桌面两列定义表——移动端三档同源，见 docs/07 §1。 */
.home-info-row {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--kcgl-space-1);
  align-items: baseline;
}

@media (min-width: 600px) {
  .home-info-row {
    grid-template-columns: 9em 1fr;
    gap: var(--kcgl-space-2);
  }
}

.home-info-row dt {
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}

.home-info-row dd {
  margin: 0;
}

.home-shell-options {
  display: flex;
  gap: 8px;
}

.home-shell-option {
  flex: 1;
  height: 44px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: #fff;
  color: var(--kcgl-color-text);
  font-size: 0.9rem;
  cursor: pointer;
  transition:
    border-color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    color var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

.home-shell-option.is-active {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
  font-weight: 600;
}

/* 触屏无 hover，按压态是唯一反馈 */
.home-shell-option:active {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
}

.home-entry-link {
  display: flex;
  align-items: center;
  width: 100%;
  height: 48px;
  padding: 0 16px;
  border: 1px solid var(--kcgl-color-border);
  border-radius: 4px;
  background: #fff;
  color: var(--kcgl-color-text);
  font: inherit;
  font-size: 0.95rem;
  cursor: pointer;
  transition:
    border-color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    color var(--kcgl-dur-fast) var(--kcgl-ease-out),
    transform var(--kcgl-dur-fast) var(--kcgl-ease-out);
}

/* 行尾尖括号 = 「点了会去另一个页面」的唯一提示。这排行只有文字和描边，
   拿掉它整行看着就是个普通按钮，用户不下手点。
   （原写法靠父容器 justify-content: space-between 把伪元素顶到右边——flex 里
   裸文本会变成一个匿名盒，碰巧生效；改成 margin-left: auto 是同一效果但读得出意图。） */
.home-entry-link::after {
  content: '›';
  margin-left: auto;
  color: var(--kcgl-color-text-sub);
  font-size: 1.3rem;
  line-height: 1;
}

/* 「电脑版页面」小标：视觉由 .kcgl-mini-tag（brand.css ⑤）给，这里只补与前面
   文字的间距——这条路是 flex 行且没设 gap，间距得自己带。 */
.home-entry-tag {
  margin-left: 8px;
}

/* 悬停高亮只在真有指针的设备生效（触屏 tap 会把 :hover 卡在最后点过的元素上） */
@media (hover: hover) {
  .home-entry-link:hover {
    border-color: var(--kcgl-color-primary);
    color: var(--kcgl-color-primary);
  }
}

/* 触屏无 hover，按压态是唯一反馈：边框变主色 + 1px 下沉 */
.home-entry-link:active {
  border-color: var(--kcgl-color-primary);
  color: var(--kcgl-color-primary);
  transform: translateY(1px);
}

.home-preparing {
  margin: 0;
  font-size: 0.9rem;
  color: var(--kcgl-color-text-sub);
}
</style>
